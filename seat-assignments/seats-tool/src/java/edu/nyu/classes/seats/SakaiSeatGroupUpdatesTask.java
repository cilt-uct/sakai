package edu.nyu.classes.seats;

import edu.nyu.classes.seats.SakaiEmails;
import edu.nyu.classes.seats.api.SeatsService;
import edu.nyu.classes.seats.models.*;
import edu.nyu.classes.seats.storage.*;
import edu.nyu.classes.seats.storage.db.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.sakaiproject.component.cover.ComponentManager;
import org.sakaiproject.component.cover.HotReloadConfigurationService;
import org.sakaiproject.exception.IdUnusedException;
import org.sakaiproject.site.api.Group;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.site.cover.SiteService;
import org.sakaiproject.user.cover.UserDirectoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SakaiSeatGroupUpdatesTask {
    private static final Logger LOG = LoggerFactory.getLogger(SakaiSeatGroupUpdatesTask.class);

    private static long WINDOW_MS = 90000;
    private static Map<String, Long> recentProcessed = new LinkedHashMap<>();
    private static AtomicLong dbTimingThresholdMs = new AtomicLong(-1);

    private static class ToProcess {
        public String siteId;
        public long lastSyncRequestTime;

        public ToProcess(String siteId, long lastSyncRequestTime) {
            this.siteId = siteId;
            this.lastSyncRequestTime = lastSyncRequestTime;
        }
    }

    private static List<ToProcess> findSitesToProcess(final long _lastTime) {
        final List<ToProcess> result = new ArrayList<>();

        DB.transaction
            ("Find sites to process",
             (DBConnection db) -> {
                db.setTimingEnabled(dbTimingThresholdMs());
                List<String> entries = new ArrayList<>(recentProcessed.keySet());

                for (String e : entries) {
                    if (recentProcessed.size() >= 1024) {
                        recentProcessed.remove(e);
                    }
                }

                db.run("SELECT q.site_id, q.last_sync_requested_time " +
                       " FROM seat_sync_queue q" +
                       " INNER JOIN sakai_site_tool sst ON sst.site_id = q.site_id AND sst.registration = 'nyu.seat-assignments'" +
                       " WHERE q.last_sync_requested_time > q.last_sync_time")
                    .executeQuery()
                    .each((row) -> {
                            String siteId = row.getString("site_id");
                            Long lastSyncRequestedTime = row.getLong("last_sync_requested_time");

                            if (recentProcessed.containsKey(siteId) &&
                                recentProcessed.get(siteId).equals(lastSyncRequestedTime)) {
                                // Already handled this one
                            } else {
                                result.add(new ToProcess(siteId, lastSyncRequestedTime));
                            }
                        });

                return null;
            });

        return result;
    }

    private static void markAsProcessed(ToProcess entry, long timestamp) {
        DB.transaction
            ("Mark site as processed",
             (DBConnection db) -> {
                db.setTimingEnabled(dbTimingThresholdMs());
                db.run("update seat_sync_queue set last_sync_time = ? where site_id = ?")
                    .param(timestamp)
                    .param(entry.siteId)
                    .executeUpdate();

                recentProcessed.put(entry.siteId, entry.lastSyncRequestTime);

                db.commit();
                return null;
            });
    }


    public static long runMTimeChecks(long lastCheck) {
        long now = System.currentTimeMillis();

        SeatsService service = (SeatsService) ComponentManager.get("edu.nyu.classes.seats.SeatsService");

        DB.transaction
            ("Mark any site or realm changed in the last 60 seconds for sync",
             (DBConnection db) -> {
                db.setTimingEnabled(dbTimingThresholdMs());
                List<String> updatedSiteIds = db.run("select site_id from sakai_site where modifiedon >= ?")
                    .param(new Date(lastCheck - 60 * 1000), new java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC")))
                    .executeQuery()
                    .getStringColumn("site_id");

                service.markSitesForSync(updatedSiteIds.toArray(new String[0]));

                List<String> updatedRealmSiteIds = db.run("select ss.site_id from sakai_site ss" +
                                                          " inner join sakai_realm sr on sr.realm_id = concat('/site/', ss.site_id)" +
                                                          " where sr.modifiedon >= ?")
                    .param(new Date(lastCheck - 60 * 1000), new java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC")))
                    .executeQuery()
                    .getStringColumn("site_id");

                service.markSitesForSync(updatedRealmSiteIds.toArray(new String[0]));

                return null;
            }
             );

        return now;
    }

    public static long handleSeatGroupUpdates(long findProcessedSince) {
        long now = System.currentTimeMillis();
        List<ToProcess> sites = findSitesToProcess(findProcessedSince);

        for (ToProcess entry : sites) {
            long startTime = System.currentTimeMillis();

            if (processSite(entry.siteId)) {
                markAsProcessed(entry, now);
                LOG.info(String.format("Processed site %s in %d ms", entry.siteId, (System.currentTimeMillis() - startTime)));
            }
        }

        return now - WINDOW_MS;
    }

    private static void notifyUser(String studentNetId, SeatGroup group, Site site) throws Exception {
        List<org.sakaiproject.user.api.User> studentUser = UserDirectoryService.getUsersByEids(Arrays.asList(new String[] { studentNetId }));

        if (studentUser.size() == 0) {
            return;
        }

        SakaiEmails.sendUserAddedEmail(studentUser.get(0), group, site);
    }

    private static boolean processSite(String siteId) {
        try {
            if (!Locks.trylockSiteForUpdate(siteId)) {
                // Currently locked.  Skip processing and try again later.
                LOG.info(String.format("Site %s already locked for update.  Skipping...", siteId));

                return false;
            }

            try {
                Site site = SiteService.getSite(siteId);

                DB.transaction
                    ("Bootstrap groups for a site and section",
                     (DBConnection db) -> {
                        try {
                            db.setTimingEnabled(dbTimingThresholdMs());
                            // Sync the rosters
                            for (Group section : site.getGroups()) {
                                if (section.getProviderGroupId() == null) {
                                    continue;
                                }

                                // Fun note: ad-hoc groups can have providers too, and this doesn't mean that
                                // the group is an official roster.  We need to check the group property to
                                // determine that.
                                if (section.getProperties().getProperty("sections_eid") == null) {
                                    if (section.getProperties().getProperty("group_prop_wsetup_created") == null) {
                                        LOG.info("Hmm... expected this group to have group_prop_wsetup_created: " + section.getId());
                                    }

                                    // Adhoc group... skip it.
                                    continue;
                                }

                                String rosterId = section.getProviderGroupId();
                                String sponsorStemName = SeatsStorage.getSponsorSectionId(db, rosterId);

                                if (!SakaiSeatsStorage.stemIsEligible(db, sponsorStemName)) {
                                    continue;
                                }


                                if (Utils.rosterToStemName(rosterId).equals(sponsorStemName)) {
                                    SeatsStorage.ensureRosterEntry(db, site.getId(), sponsorStemName, Optional.empty());
                                } else {
                                    SeatsStorage.ensureRosterEntry(db, site.getId(), sponsorStemName, Optional.of(rosterId));
                                }
                            }

                            for (SeatSection section : SeatsStorage.siteSeatSections(db, siteId)) {
                                if (section.provisioned) {
                                    SeatsStorage.SyncResult syncResult = SakaiSeatsStorage.syncGroupsToSection(db, section, site);

                                    if (section.listGroups().size() > 1) {
                                        for (Map.Entry<String, List<Member>> entry : syncResult.adds.entrySet()) {
                                            String groupId = entry.getKey();

                                            for (Member m : entry.getValue()) {
                                                if (m.isInstructor()) {
                                                    // No email sent to instructors
                                                    continue;
                                                }

                                                Optional<SeatGroup> group = section.fetchGroup(groupId);
                                                if (group.isPresent()) {
                                                    try {
                                                        notifyUser(m.netid, group.get(), site);
                                                    } catch (Exception e) {
                                                        LOG.error(String.format("Failure while notifying user '%s' in group '%s' for site '%s': %s",
                                                                                m.netid,
                                                                                group.get().id,
                                                                                site.getId(),
                                                                                e));
                                                        e.printStackTrace();
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    List<Member> sectionMembers = new SakaiSeatsStorage().getMembersForSection(db, section);
                                    SeatsStorage.bootstrapGroupsForSection(db, section, sectionMembers, 1, SeatsStorage.SelectionType.RANDOM);
                                }
                            }

                            db.commit();

                            return null;
                        } catch (Exception e) {
                            db.rollback();
                            throw e;
                        }
                    });

                return true;
            } catch (IdUnusedException e) {
                LOG.info("SeatJob: site not found: " + siteId);
                return true;
            } catch (Exception e) {
                LOG.error(String.format("Error while processing site '%s': ", siteId) + e);
                e.printStackTrace();
                return true;
            }
        } finally {
            Locks.unlockSiteForUpdate(siteId);
        }
    }

    public static void findChangedInstructionModes() {
        long now = System.currentTimeMillis();

        SeatsService service = (SeatsService) ComponentManager.get("edu.nyu.classes.seats.SeatsService");
        boolean performDelete = "true".equals(HotReloadConfigurationService.getString("seats.auto-delete", "false"));

        DB.transaction
            ("Handle changed instruction modes for seating tool",
             (DBConnection db) -> {
                try {
                    db.setTimingEnabled(dbTimingThresholdMs());

                    // Find any online roster that has cohorts but should not.
                    db.run("select sec.primary_stem_name," +
                           "  sec.site_id," +
                           "  sec.id as section_id," +
                           "  to_char(ssp.value) as override_to_blended" +
                           " from SEAT_GROUP_SECTION sec" +
                           " inner join sakai_site ss on ss.site_id = sec.site_id" +
                           " inner join NYU_T_COURSE_CATALOG cc on sec.primary_stem_name = cc.stem_name AND cc.instruction_mode not in ('OB', 'P')" +
                           " left join sakai_site_property ssp on ssp.site_id = sec.site_id AND ssp.name = 'OverrideToBlended'")
                        .executeQuery()
                        .each((row) -> {
                                String props = row.getString("override_to_blended");

                                if (props != null && Arrays.asList(props.split(" *, *")).contains(row.getString("primary_stem_name"))) {
                                    // You're OK.
                                } else {
                                    LOG.info(String.format("Removing Seats section for online roster '%s' in site '%s'",
                                                           row.getString("primary_stem_name"),
                                                           row.getString("site_id")));
                                    SeatsStorage.getSeatSection(db, row.getString("section_id"), row.getString("site_id"))
                                        .ifPresent((section) -> {
                                                try {
                                                    if (performDelete) {
                                                        SeatsStorage.deleteSection(db, section);
                                                    } else {
                                                        LOG.error("Delete skipped due to seats.auto-delete=false");
                                                    }
                                                } catch (SQLException e) {
                                                    LOG.error("Failure during delete: " + e);
                                                    e.printStackTrace();
                                                }
                                            });
                                }
                            });

                    // Find any cohorts that linked to a detached roster
                    db.run("select sec.primary_stem_name, sec.site_id, sec.id as section_id" +
                            " from SEAT_GROUP_SECTION sec" +
                           " inner join sakai_site ss on ss.site_id = sec.site_id" +
                            " left join SAKAI_REALM sr on sr.realm_id = concat('/site/', sec.site_id)" +
                            " left join SAKAI_REALM_PROVIDER srp on srp.realm_key = sr.realm_key and srp.provider_id = replace(sec.primary_stem_name, ':', '_')" +
                            " where srp.provider_id is null")
                            .executeQuery()
                            .each((row) -> {
                                LOG.info(String.format("Removing Seats section for detached roster '%s' in site '%s'",
                                        row.getString("primary_stem_name"),
                                        row.getString("site_id")));

                                SeatsStorage.getSeatSection(db, row.getString("section_id"), row.getString("site_id"))
                                        .ifPresent((section) -> {
                                            try {
                                                if (performDelete) {
                                                    SeatsStorage.deleteSection(db, section);
                                                } else {
                                                    LOG.error("Delete skipped due to seat.auto-delete=false");
                                                }
                                            } catch (SQLException e) {
                                                LOG.error("Failure during delete: " + e);
                                                e.printStackTrace();
                                            }
                                        });
                            });


                    // Find any in-person or blended roster that hasn't been bootstrapped yet.
                    db.run("select distinct ss.site_id" +
                           " from nyu_t_course_catalog cc" +
                           " inner join sakai_realm_provider srp on srp.provider_id = replace(cc.stem_name, ':', '_')" +
                           " inner join sakai_realm sr on sr.realm_key = srp.realm_key" +
                           " inner join sakai_site ss on concat('/site/', ss.site_id) = sr.realm_id" +
                           " inner join sakai_site_tool sst on sst.site_id = ss.site_id AND sst.registration = 'nyu.seat-assignments'" +
                           " left join seat_group_section_rosters sgsr on sgsr.sakai_roster_id = srp.provider_id" +
                           " where sgsr.sakai_roster_id is null AND  cc.instruction_mode in ('OB', 'P')")
                        .executeQuery()
                        .each((row) -> {
                                service.markSitesForSync(row.getString("site_id"));
                            });

                    db.commit();

                    return null;
                } catch (Exception e) {
                    db.rollback();
                    throw e;
                }
            });
    }

    public static void setDBTimingThresholdMs(long ms) {
        dbTimingThresholdMs.set(ms);
    }

    private static long dbTimingThresholdMs() {
        return dbTimingThresholdMs.get();
    }
}
