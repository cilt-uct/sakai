package org.sakaiproject.portal.charon.handlers;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.sakaiproject.authz.api.AuthzGroupService;
import org.sakaiproject.authz.cover.SecurityService;
import org.sakaiproject.component.cover.ComponentManager;
import org.sakaiproject.component.cover.HotReloadConfigurationService;
import org.sakaiproject.db.api.SqlService;
import org.sakaiproject.portal.api.PortalHandlerException;
import org.sakaiproject.tool.api.Session;
import org.sakaiproject.tool.cover.SessionManager;
import org.sakaiproject.user.cover.UserDirectoryService;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.sql.*;
import java.util.*;

import java.io.IOException;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.*;

public class BrightspaceMigratorHandler extends BasePortalHandler {

    private static class MigratorDatabase {
        private static Log M_log = LogFactory.getLog(MigratorDatabase.class);

        public static AtomicReference<MigratorDatabase> instance = new AtomicReference<>(new MigratorDatabase());

        private String masterDBPath;
        private String workingDBPath;
        private HikariDataSource dataSource;


        // True if we currently have a refresh thread running
        AtomicBoolean refreshInProgress = new AtomicBoolean(false);

        // The time we last looked to see if a new DB version was available
        AtomicLong lastRefreshCheckTime = new AtomicLong(0);

        // The time our refresh last failed to finish (e.g. due to an IO error of some
        // kind).  Used to avoid getting stuck in a loop of constantly copying and
        // failing.
        AtomicLong lastRefreshFailureTime = new AtomicLong(0);


        public MigratorDatabase() {
            this.masterDBPath = HotReloadConfigurationService.getString("brightspace.selfservice.masterdb", "/shared/sakai/self_service.db");
            this.workingDBPath = HotReloadConfigurationService.getString("brightspace.selfservice.workingdb", "/tmp/self_service_workdb.db");

            try {
                copyLatest();
            } catch (Exception e) {
                M_log.error("FAILURE LOADING MIGRATOR DATABASE: " + e);
                e.printStackTrace();

                throw new RuntimeException(e);
            }

            openWorkingDatabase();
        }

        private void openWorkingDatabase() {
            HikariDataSource ds = new HikariDataSource();
            ds.setDriverClassName("org.sqlite.JDBC");
            ds.setJdbcUrl("jdbc:sqlite:" + workingDBPath);
            ds.setMaxLifetime(30000);

            this.dataSource = ds;
        }

        public void close() {
            dataSource.close();
        }

        // NOTE: assumes masterDBPath is written atomically.  Make sure you write to a
        // temp file + rename when generating it.
        private void copyLatest() {
            try {
                Path tempPath = Paths.get(workingDBPath + "." + java.util.UUID.randomUUID().toString());

                Files.copy(Paths.get(masterDBPath),
                           tempPath,
                           StandardCopyOption.REPLACE_EXISTING);

                Files.move(tempPath, Paths.get(workingDBPath), StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                M_log.error("Error from BrightspaceMigratorHandler::copyLatest: " + e);
                e.printStackTrace();

                throw new RuntimeException(e);
            }
        }

        private boolean needsRefresh() {
            if (!new File(workingDBPath).exists() || new File(masterDBPath).lastModified() > new File(workingDBPath).lastModified()) {
                return true;
            }

            // Or if our underlying config has changed
            String updatedMasterDBPath = HotReloadConfigurationService.getString("brightspace.selfservice.masterdb", "/shared/sakai/self_service.db");
            String updatedWorkingDBPath = HotReloadConfigurationService.getString("brightspace.selfservice.workingdb", "/tmp/self_service_workdb.db");

            if (!this.masterDBPath.equals(updatedMasterDBPath)) {
                return true;
            }

            if (!this.workingDBPath.equals(updatedWorkingDBPath)) {
                return true;
            }

            return false;
        }

        public void maybeRefreshDB() {
            if (refreshInProgress.get()) {
                return;
            }

            long now = System.currentTimeMillis();

            if ((now - lastRefreshCheckTime.get()) < 60000) {
                return;
            }

            if ((now - lastRefreshFailureTime.get()) < 3600000) {
                // If we failed in the last hour, don't retry
                return;
            }

            if (refreshInProgress.compareAndSet(false, true)) {
                startRefreshThread();
            }
        }

        private void startRefreshThread() {
            Thread refresh = new Thread(() -> {
                    Thread.currentThread().setName("BrightspaceMigratorHandler::startRefreshThread");
                    M_log.info("BrightspaceMigratorHandler::startRefreshThread starting up");
                    try {
                        lastRefreshCheckTime.set(System.currentTimeMillis());

                        if (needsRefresh()) {
                            copyLatest();
                        } else {
                            return;
                        }

                        // reopen the datasource, closing the other
                        HikariDataSource oldDataSource = this.dataSource;

                        this.openWorkingDatabase();

                        M_log.info("BrightspaceMigratorHandler::startRefreshThread new database installed");

                        if (oldDataSource == null) {
                            M_log.info("BrightspaceMigratorHandler::startRefreshThread no previous pool to shut down");
                            return;
                        }

                        try {
                            HikariPoolMXBean poolBean = oldDataSource.getHikariPoolMXBean();
                            int retry = 0;
                            while (poolBean.getActiveConnections() > 0 && retry < 10) {
                                retry++;
                                poolBean.softEvictConnections();

                                try {
                                    Thread.sleep(1000);
                                } catch (InterruptedException e) {
                                }
                            }

                            oldDataSource.close();
                            M_log.info("BrightspaceMigratorHandler::startRefreshThread closed old pool");
                        } catch (Exception e) {
                            M_log.error("Failure while closing old data source", e);
                        }
                    } catch (Throwable t) {
                        lastRefreshFailureTime.set(System.currentTimeMillis());

                        M_log.error("BrightspaceMigratorHandler::startRefreshThread caught error: " + t);
                        t.printStackTrace();
                    } finally {
                        refreshInProgress.set(false);
                    }
            });

            refresh.setDaemon(true);
            refresh.start();
        }

        private Connection getConnection() throws SQLException {
            return dataSource.getConnection();
        }

        private static String escapeQuery(String s) {
            return s.replaceAll("[^\\p{L}\\p{N}]", " ");
        }

        public InstructorSitePage instructorSites(String netid, String termFilter, String queryFilter, int page, int page_size) throws SQLException {
            Map<String, SiteToArchive> results = new LinkedHashMap<>();

            List<String> replacements = new ArrayList<>();
            replacements.add(netid);

            String termFilterSQL = "";
            if (termFilter != null) {
                termFilterSQL = " and sss.term = ? ";
                replacements.add(termFilter);
            }

            String userFilter = "ma.netid = ?";

            if (SecurityService.isSuperUser()) {
                userFilter = "(ma.netid = ? OR 1 = 1)";
            }

            String querySubSelect = "select site_id from NYU_T_SELFSERV_SITES";
            if (queryFilter != null && queryFilter.length() >= 3) {
                querySubSelect = ("select distinct ma.site_id" +
                                  "  from NYU_T_SELFSERV_MIG_ACCESS ma" +
                                  "  where " + userFilter +
                                  "    and ma.site_id in (" +
                                  "      select site_id from NYU_T_SELFSERV_INSTRS ssi where ssi.netid = ?" +
                                  "      union all" +
                                  "      select site_id from NYU_T_SELFSERV_ROSTERS ssr where ssr.roster_id = ?" +
                                  "      union all" +
                                  "      select site_id from NYU_T_SELFSERV_SITES sssq where sssq.site_id = ?" +
                                  "      union all" +
                                  "      select site_id from textsearch where site_text match ?" +
                                  "    )"
                                  );

                replacements.add(netid);
                replacements.add(queryFilter);
                replacements.add(queryFilter);
                replacements.add(queryFilter);
                replacements.add(escapeQuery(queryFilter));
            }

            Connection db = null;
            try {
                db = getConnection();

                String pageQuery = "select sss.*" +
                    " from NYU_T_SELFSERV_MIG_ACCESS ma" +
                    " inner join NYU_T_SELFSERV_SITES sss on sss.site_id = ma.site_id" +
                    " where " + userFilter +
                    termFilterSQL +
                    " and sss.site_id in (" + querySubSelect + ")" +
                    " order by sss.term_sort desc, sss.site_id asc" +
                    String.format(" limit %d offset %d", page_size + 1, page_size * page);

                // Admin gets all sites with no restriction
                if (SecurityService.isSuperUser()) {
                    pageQuery = "select sss.*" +
                        " from NYU_T_SELFSERV_SITES sss" +
                        " where (? = 'bogus' OR 1 = 1)" +
                        termFilterSQL +
                        " and sss.site_id in (" + querySubSelect + ")" +
                        " order by sss.term_sort desc, sss.site_id asc" +
                        String.format(" limit %d offset %d", page_size + 1, page_size * page);
                }

                try (PreparedStatement ps = db.prepareStatement(pageQuery)) {
                    for (int i=0; i<replacements.size(); i++) {
                        ps.setString(i+1, replacements.get(i));
                    }

                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String siteId = rs.getString("site_id");
                            String termEid = rs.getString("term");

                            SiteToArchive siteToArchive = new SiteToArchive();
                            siteToArchive.siteId = siteId;
                            siteToArchive.title = rs.getString("title");
                            siteToArchive.term = rs.getString("term");
                            siteToArchive.department = rs.getString("department");
                            siteToArchive.school = rs.getString("school");
                            siteToArchive.location = rs.getString("location");
                            if (siteToArchive.term == null) {
                                siteToArchive.term = StringUtils.capitalize(rs.getString("site_type"));
                            }

                            results.put(siteId, siteToArchive);
                        }
                    }
                }

                // Instructors
                List<String> siteIds = new ArrayList<>(results.keySet());
                String placeholders = results.keySet().stream().map(_siteId -> "?").collect(Collectors.joining(","));
                try (PreparedStatement ps = db.prepareStatement("select site_id, netid, fname, lname" +
                                                                " from NYU_T_SELFSERV_INSTRS" +
                                                                " where site_id in (" + placeholders + ")")) {
                    for (int i = 0; i < siteIds.size(); i++) {
                        ps.setString(i + 1, siteIds.get(i));
                    }

                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String siteId = rs.getString("site_id");

                            SiteToArchive siteToArchive = results.get(siteId);

                            String instructorNetid = rs.getString("netid");
                            String instructorFirstName = rs.getString("fname");
                            String instructorLastName = rs.getString("lname");

                            if (instructorNetid != null) {
                                if (instructorFirstName != null && instructorLastName != null) {
                                    siteToArchive.instructors.put(instructorNetid, String.format("%s %s (%s)", instructorFirstName, instructorLastName, instructorNetid));
                                } else {
                                    siteToArchive.instructors.put(instructorNetid, instructorNetid);
                                }
                            }
                        }
                    }
                }


                // Rosters
                try (PreparedStatement ps = db.prepareStatement("select site_id, roster_id" +
                                                                " from NYU_T_SELFSERV_ROSTERS" +
                                                                " where site_id in (" + placeholders + ")")) {
                    for (int i = 0; i < siteIds.size(); i++) {
                        ps.setString(i + 1, siteIds.get(i));
                    }

                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String siteId = rs.getString("site_id");

                            SiteToArchive siteToArchive = results.get(siteId);

                            siteToArchive.rosters.add(rs.getString("roster_id"));
                        }
                    }
                }

                InstructorSitePage result = new InstructorSitePage();
                result.sites = new ArrayList<>(results.values());
                result.hasNextPage = result.sites.size() > page_size;
                result.hasPreviousPage = page > 0;
                if (result.hasNextPage) {
                    // Remove our extra sentinel value
                    result.sites.remove(result.sites.size() - 1);
                }

                return result;
            } catch (SQLException e) {
                M_log.error("instructorSites: " + e);
                e.printStackTrace();

                InstructorSitePage result = new InstructorSitePage();
                result.sites = new ArrayList<>();
                result.hasNextPage = false;
                result.hasPreviousPage = false;

                return result;
            } finally {
                if (db != null) {
                    db.close();
                }
            }
        }

        public List<String> instructorTerms(String netid) throws SQLException {
            List<String> result = new ArrayList<>();

            Connection db = null;
            try {
                db = getConnection();

                try (PreparedStatement ps = db.prepareStatement("select a.term" +
                                                                " from (" +
                                                                "  select distinct sss.term, sss.term_sort" +
                                                                "   from NYU_T_SELFSERV_MIG_ACCESS ma" +
                                                                "   inner join NYU_T_SELFSERV_SITES sss on sss.site_id = ma.site_id" +
                                                                "   where ma.netid = ?" +
                                                                " ) a" +
                                                                "  order by a.term_sort desc")) {
                    ps.setString(1, netid);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            result.add(rs.getString("term"));
                        }
                    }
                }
            } catch (SQLException e) {
                M_log.error(".instructorTerms: " + e);
                e.printStackTrace();
            } finally {
                if (db != null) {
                    db.close();
                }
            }

            return result;
        }

        private PreparedStatement matchQueryFor(Connection db, String netid, String schoolCode, String maybeDepartment) throws SQLException {
            if (maybeDepartment == null) {
                PreparedStatement ps = db.prepareStatement("select count(1) count" +
                                                           " from nyu_t_selfserv_mig_access ma" +
                                                           " inner join nyu_t_selfserv_sites sss on sss.site_id = ma.site_id" +
                                                           " where ma.netid = ? AND sss.school = ?");
                ps.setString(1, netid);
                ps.setString(2, schoolCode);

                return ps;
            } else {
                PreparedStatement ps = db.prepareStatement("select count(1) count" +
                                                           " from nyu_t_selfserv_mig_access ma" +
                                                           " inner join nyu_t_selfserv_sites sss on sss.site_id = ma.site_id" +
                                                           " where ma.netid = ? AND sss.school = ? AND sss.department = ?");
                ps.setString(1, netid);
                ps.setString(2, schoolCode);
                ps.setString(3, maybeDepartment);

                return ps;
            }
        }

        public boolean userHasMatchingSite(String netid, String schoolCode, String maybeDepartment) throws SQLException {
            Connection db = null;
            try {
                db = getConnection();

                try (PreparedStatement ps = matchQueryFor(db, netid, schoolCode, maybeDepartment)) {
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            return rs.getInt("count") > 0;
                        }
                    }
                }
            } catch (SQLException e) {
                M_log.error("userHasMatchingSite: " + e);
                e.printStackTrace();
            } finally {
                if (db != null) {
                    db.close();
                }
            }

            return false;
        }

        public static class InstructorSitePage {
            public List<SiteToArchive> sites;
            public boolean hasNextPage;
            public boolean hasPreviousPage;
        }
    }


    private static final String URL_FRAGMENT = "brightspace-migrator";
    private static final String SESSION_KEY_ALLOWED_TO_MIGRATE = "NYU_ALLOWED_TO_MIGRATE";

    private static final String SESSION_KEY_NETID_LAST_CHECK_TIME = "SESSION_KEY_NETID_LAST_CHECK_TIME";
    private static final String SESSION_KEY_NETID_LAST_VALUE = "SESSION_KEY_NETID_LAST_VALUE";
    private static final String SESSION_KEY_RULE_LAST_CHECK_TIME = "SESSION_KEY_RULE_LAST_CHECK_TIME";
    private static final String SESSION_KEY_RULE_LAST_VALUE = "SESSION_KEY_RULE_LAST_VALUE";

    private static final int QUERY_BATCH_SIZE = 1000;

    private static final int PAGE_SIZE = 50;

    private SqlService sqlService;
    private static Log M_log = LogFactory.getLog(BrightspaceMigratorHandler.class);

    private AuthzGroupService authzGroupService = (AuthzGroupService)ComponentManager.get("org.sakaiproject.authz.api.AuthzGroupService");

    private static long NETID_RECHECK_INTERVAL_MS = 60000;
    private static long RULE_RECHECK_INTERVAL_MS = 3600000;

    public BrightspaceMigratorHandler()
    {
        setUrlFragment(BrightspaceMigratorHandler.URL_FRAGMENT);
        if(sqlService == null) {
            sqlService = (SqlService) org.sakaiproject.component.cover.ComponentManager.get("org.sakaiproject.db.api.SqlService");
        }
    }

    private void populateRequestStatus(List<SiteToArchive> sites) {
        if (sites.isEmpty()) {
            return;
        }

        Connection db = null;
        try {
            db = sqlService.borrowConnection();

            String placeholders = sites.stream().map(_s -> "?").collect(Collectors.joining(","));

            try (PreparedStatement ps = db.prepareStatement("select * from NYU_T_SITE_ARCHIVES_QUEUE where site_id in (" + placeholders + ")")) {
                for (int i = 0; i < sites.size(); i++) {
                    ps.setString(i + 1, sites.get(i).siteId);
                }

                Map<String, SiteToArchive> siteIdToSiteToArchive = new HashMap<>();
                for (SiteToArchive s : sites) {
                    siteIdToSiteToArchive.put(s.siteId, s);
                }

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        SiteArchiveRequest request = new SiteArchiveRequest();
                        request.queuedAt = rs.getLong("queued_at");
                        request.queuedBy = rs.getString("queued_by");
                        request.archivedAt = rs.getLong("archived_at");
                        request.uploadedAt = rs.getLong("uploaded_at");
                        request.completedAt = rs.getLong("completed_at");
                        request.brightspaceOrgUnitId = rs.getLong("brightspace_org_unit_id");

                        SiteToArchive siteToArchive = siteIdToSiteToArchive.get(rs.getString("site_id"));
                        siteToArchive.requests.add(request);
                    }
                }
            }
        } catch (SQLException e) {
            M_log.warn(this + ".populateRequestStatus: " + e);
        } finally {
            if (db != null) {
                sqlService.returnConnection(db);
            }
        }
    }

    @Override
    public int doGet(String[] parts, HttpServletRequest req, HttpServletResponse res,
                     Session session) throws PortalHandlerException
    {
        if ((parts.length >= 2) && (parts[1].equals(BrightspaceMigratorHandler.URL_FRAGMENT)))
        {
            try
            {
                MigratorDatabase.instance.get().maybeRefreshDB();

                if (!isAllowedToMigrateSitesToBrightspace()) {
                    return NEXT;
                }

                JSONObject obj = new JSONObject();

                JSONArray sitesJSON = new JSONArray();

                String termFilter = StringUtils.trimToNull(req.getParameter("term"));
                String queryFilter = StringUtils.trimToNull(req.getParameter("q"));
                String pageStr = StringUtils.trimToNull(req.getParameter("page"));

                int page = 0;
                if (pageStr != null) {
                    page = Integer.valueOf(pageStr);
                }

                String netid = UserDirectoryService.getCurrentUser().getEid();

                MigratorDatabase.InstructorSitePage sitesPage = MigratorDatabase.instance.get().instructorSites(netid, termFilter, queryFilter, page, PAGE_SIZE);

                populateRequestStatus(sitesPage.sites);

                obj.put("page", page);
                obj.put("has_previous_page", sitesPage.hasPreviousPage);
                obj.put("has_next_page", sitesPage.hasNextPage);

                obj.put("can_remigrate", SecurityService.isSuperUser());

                for (SiteToArchive site : sitesPage.sites) {
                    JSONObject siteJSON = new JSONObject();
                    siteJSON.put("site_id", site.siteId);
                    siteJSON.put("title", site.title);
                    siteJSON.put("term", site.term);

                    JSONArray rostersJSON = new JSONArray();
                    for (String roster : site.rosters) {
                        rostersJSON.add(roster);
                    }
                    siteJSON.put("rosters", rostersJSON);

                    JSONArray instructorsJSON = new JSONArray();
                    for (Map.Entry<String, String> instructor : site.instructors.entrySet()) {
                        JSONObject instructorJSON = new JSONObject();
                        instructorJSON.put("netid", instructor.getKey());
                        instructorJSON.put("display", instructor.getValue());
                        instructorsJSON.add(instructorJSON);
                    }
                    siteJSON.put("instructors", instructorsJSON);

                    boolean queued = false;
                    for (SiteArchiveRequest request : site.requests) {
                        siteJSON.put("queued_by", request.queuedBy);
                        siteJSON.put("queued_at", request.queuedAt);
                        siteJSON.put("archived_at", request.archivedAt);
                        siteJSON.put("uploaded_at", request.uploadedAt);
                        siteJSON.put("completed_at", request.completedAt);
                        siteJSON.put("status", request.getStatus());
                        siteJSON.put("brightspace_org_unit_id", request.brightspaceOrgUnitId);
                        queued = true;
                    }
                    siteJSON.put("queued", queued);
                    sitesJSON.add(siteJSON);
                }

                obj.put("sites", sitesJSON);

                JSONArray termsJSON = new JSONArray();
                for (String term : MigratorDatabase.instance.get().instructorTerms(netid)) {
                    termsJSON.add(term);
                }
                obj.put("terms", termsJSON);

                res.setHeader("Content-type", "text/json");
                res.getWriter().write(obj.toString());

                return END;
            }
            catch (Exception ex)
            {
                throw new PortalHandlerException(ex);
            }
        }
        else
        {
            return NEXT;
        }
    }

    @Override
    public int doPost(String[] parts, HttpServletRequest req,
                      HttpServletResponse res, Session session)
            throws PortalHandlerException {
        if ((parts.length >= 2) && (parts[1].equals(BrightspaceMigratorHandler.URL_FRAGMENT)))
        {
            try {
                if (!isAllowedToMigrateSitesToBrightspace()) {
                    return NEXT;
                }

                String siteId = StringUtils.trimToNull(req.getParameter("site_id"));
                if (siteId != null && SecurityService.unlock("site.upd", "/site/" + siteId)) {
                    queueSiteForArchive(siteId, SecurityService.isSuperUser());
                }

                res.setHeader("Content-type", "text/json");
                res.getWriter().write("{\"success\":true}");

                return END;
            } catch (Exception e) {
                throw new PortalHandlerException(e);
            }
        }

        return NEXT;
    }


    public boolean isAllowedToMigrateSitesToBrightspace() {
        if (SecurityService.isSuperUser()) {
            return true;
        }

        if (!"true".equals(HotReloadConfigurationService.getString("brightspace.selfservice.enabled", "true"))) {
            // Self-service is disabled!
            return false;
        }

        String netid = UserDirectoryService.getCurrentUser().getEid();
        Session session = SessionManager.getCurrentSession();

        long timerStartTime = System.currentTimeMillis();

        try {
            if (netIdExplicitlyAllowed(netid, session)) {
                return true;
            } else if (allowedByRule(netid, session)) {
                return true;
            } else {
                return false;
            }
        } finally {
            long timerEndTime = System.currentTimeMillis();
            long duration = timerEndTime - timerStartTime;

            if (duration > 2000) {
                M_log.warn(String.format("isAllowedToMigrateSitesToBrightspace took %d ms to complete", duration));
            }
        }
    }

    public boolean netIdExplicitlyAllowed(String netid, Session session) {
        Long lastCheckTime = (Long)session.getAttribute(SESSION_KEY_NETID_LAST_CHECK_TIME);
        if (lastCheckTime == null) {
            lastCheckTime = 0L;
        }

        if ((System.currentTimeMillis() - lastCheckTime) > NETID_RECHECK_INTERVAL_MS) {
            // Refresh our value
            boolean result = false;

            Connection db = null;
            try {
                db = sqlService.borrowConnection();

                // First, let's check if the netid has been approved
                try (PreparedStatement ps = db.prepareStatement("select count(1) " +
                                                                " from NYU_T_SELF_SERVICE_ACCESS ssa" +
                                                                " where ssa.netid = ?" +
                                                                " and ssa.rule_type = 'netid'")) {
                    ps.setString(1, netid);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            result = rs.getInt(1) > 0;
                        }
                    }
                }
            } catch (SQLException e) {
                M_log.warn(this + ".netIdExplicitlyAllowed: " + e);
            } finally {
                if (db != null) {
                    sqlService.returnConnection(db);
                }
            }

            session.setAttribute(SESSION_KEY_NETID_LAST_CHECK_TIME, System.currentTimeMillis());
            session.setAttribute(SESSION_KEY_NETID_LAST_VALUE, result);
        }

        return (boolean)session.getAttribute(SESSION_KEY_NETID_LAST_VALUE);
    }

    public boolean allowedByRule(String netid, Session session) {
        Long lastCheckTime = (Long)session.getAttribute(SESSION_KEY_RULE_LAST_CHECK_TIME);
        if (lastCheckTime == null) {
            lastCheckTime = 0L;
        }

        if ((System.currentTimeMillis() - lastCheckTime) > RULE_RECHECK_INTERVAL_MS) {
            // Refresh our value
            boolean result = false;

            Connection db = null;
            try {
                db = sqlService.borrowConnection();

                try (PreparedStatement ps = db.prepareStatement("select * " +
                                                                " from NYU_T_SELF_SERVICE_ACCESS ssa" +
                                                                " where ssa.rule_type != 'netid'");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (MigratorDatabase.instance.get().userHasMatchingSite(netid, rs.getString("school"), rs.getString("department"))) {
                            result = true;
                            break;
                        }
                    }
                }
            } catch (SQLException e) {
                M_log.warn(this + ".netIdExplicitlyAllowed: " + e);
            } finally {
                if (db != null) {
                    sqlService.returnConnection(db);
                }
            }

            session.setAttribute(SESSION_KEY_RULE_LAST_CHECK_TIME, System.currentTimeMillis());
            session.setAttribute(SESSION_KEY_RULE_LAST_VALUE, result);
        }

        return (boolean)session.getAttribute(SESSION_KEY_RULE_LAST_VALUE);
    }


    private void queueSiteForArchive(String siteId, boolean rearchiveAllowed) {
        String netid = UserDirectoryService.getCurrentUser().getEid();

        Connection db = null;
        boolean autoCommit = true;
        try {
            db = sqlService.borrowConnection();
            autoCommit = db.getAutoCommit();
            db.setAutoCommit(false);

            if (rearchiveAllowed) {
                try (PreparedStatement ps = db.prepareStatement("delete from NYU_T_SITE_ARCHIVES_QUEUE where site_id = ?")) {
                    ps.setString(1, siteId);
                    ps.executeUpdate();
                }
            }

            try (PreparedStatement ps = db.prepareStatement("insert into NYU_T_SITE_ARCHIVES_QUEUE (site_id, queued_at, queued_by)" +
                    " values (?, ?, ?)")) {
                ps.setString(1, siteId);
                ps.setLong(2, System.currentTimeMillis());
                ps.setString(3, netid);
                ps.executeUpdate();
            }

            db.commit();
        } catch (SQLException e) {
            M_log.error(this + ".queueSiteForArchive: " + e);
        } finally {
            if (db != null) {
                try {
                    db.setAutoCommit(autoCommit);
                } catch (SQLException e) {}

                sqlService.returnConnection(db);
            }
        }
    }

    private static class SiteToArchive {
        public String siteId;
        public String title;
        public String term;
        public String school;
        public String department;
        public String location;
        public List<SiteArchiveRequest> requests = new ArrayList<>();
        public Set<String> rosters = new HashSet<>();
        public Map<String, String> instructors = new HashMap<>();
    }

    private static class SiteArchiveRequest {
        public String queuedBy;
        public long queuedAt;
        public long archivedAt;
        public long uploadedAt;
        public long completedAt;
        public long brightspaceOrgUnitId;

        public String getStatus() {
            if (this.completedAt > 0) {
                return "COMPLETED";
            } else if (this.uploadedAt > 0) {
                return "UPLOADED";
            } else if (this.archivedAt > 0) {
                return "ARCHIVED";
            } else if (this.queuedAt > 0) {
                return "QUEUED";
            } else {
                return "READY_FOR_ARCHIVE";
            }
        }
    }
}


//    CREATE TABLE "NYU_T_SITE_ARCHIVES_QUEUE"
//        ("SITE_ID" VARCHAR2(100) NOT NULL,
//        "QUEUED_BY" VARCHAR2(100),
//        "QUEUED_AT" NUMBER(38,0),
//        "ARCHIVED_AT" NUMBER(38,0),
//        "UPLOADED_AT" NUMBER(38,0),
//        "COMPLETED_AT" NUMBER(38,0),
//        "BRIGHTSPACE_ORG_UNIT_ID" NUMBER(38,0),
//        CONSTRAINT "NYU_T_SITE_ARCHIVES_QUEUE_PK" PRIMARY KEY ("SITE_ID"))

