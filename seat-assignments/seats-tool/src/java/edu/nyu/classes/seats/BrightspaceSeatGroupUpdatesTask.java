package edu.nyu.classes.seats;

import edu.nyu.classes.seats.brightspace.BrightspaceClient;
import edu.nyu.classes.seats.brightspace.BrightspaceSection;
import edu.nyu.classes.seats.brightspace.BrightspaceSectionInfo;
import edu.nyu.classes.seats.models.Member;
import edu.nyu.classes.seats.models.SeatGroup;
import edu.nyu.classes.seats.models.SeatSection;
import edu.nyu.classes.seats.storage.BrightspaceSeatsStorage;
import edu.nyu.classes.seats.storage.Locks;
import edu.nyu.classes.seats.storage.SeatsStorage;
import edu.nyu.classes.seats.storage.db.DB;
import edu.nyu.classes.seats.storage.db.DBConnection;
import org.sakaiproject.component.cover.HotReloadConfigurationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.sql.SQLException;

public class BrightspaceSeatGroupUpdatesTask {
    private static final Logger LOG = LoggerFactory.getLogger(BrightspaceSeatGroupUpdatesTask.class);

    private static long WINDOW_MS = 30000;
    private static Map<String, Long> recentProcessed = new LinkedHashMap<>();

    private static class ToProcess {
        public String siteId;
        public long lastSyncRequestTime;

        public ToProcess(String siteId, long lastSyncRequestTime) {
            this.siteId = siteId;
            this.lastSyncRequestTime = lastSyncRequestTime;
        }
    }

    private static int maxSecondsBeforeResync() {
        return Integer.valueOf(HotReloadConfigurationService.getString("seats.brightspace.max_seconds_before_resync", "1800"));
    }

    private static ExecutorService immediateSyncPool = Executors.newFixedThreadPool(8);
    private static ExecutorService emailPool = Executors.newFixedThreadPool(8);

    public static boolean hasSyncedLately(String siteId) {
        long lastSyncTime = timeLastSynced(siteId);

        return (System.currentTimeMillis() - lastSyncTime) <= (maxSecondsBeforeResync() * 1000);
    }

    public static Future<Boolean> syncImmediate(final BrightspaceClient brightspace, final String siteId) {
        final ToProcess processMe = new ToProcess(siteId, System.currentTimeMillis());

        return immediateSyncPool.submit(() -> {
                long lastSyncTime = timeLastSynced(processMe.siteId);
                long startTime = System.currentTimeMillis();

                if ((startTime - lastSyncTime) > maxSecondsBeforeResync() * 1000) {
                    if (processSite(processMe.siteId, brightspace)) {
                        markAsProcessed(processMe, startTime);
                        LOG.info(String.format("Processed immediate site %s in %d ms", processMe.siteId, (System.currentTimeMillis() - startTime)));

                        return true;
                    } else {
                        return false;
                    }
                } else {
                    return false;
                }
            });
    }

    private static long timeLastSynced(String siteId) {
        return DB.transaction
            ("Return the time we last synced a site",
             (DBConnection db) -> {
                return db.run("select last_sync_time from seat_sync_queue where site_id = ?")
                    .param(siteId)
                    .executeQuery()
                    .oneLong()
                    .orElse(0L);
            });
    }


    private static List<ToProcess> findSitesToProcess(final long _lastTime) {
        final List<ToProcess> result = new ArrayList<>();

        DB.transaction
            ("Find sites to process",
             (DBConnection db) -> {
                List<String> entries = new ArrayList<>(recentProcessed.keySet());

                for (String e : entries) {
                    if (recentProcessed.size() >= 1024) {
                        recentProcessed.remove(e);
                    }
                }

                db.run("SELECT q.site_id, q.last_sync_requested_time " +
                       " FROM seat_sync_queue q" +
                       " WHERE q.site_id like 'brightspace:%' AND q.last_sync_requested_time > q.last_sync_time")
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
                int updates = db.run("update seat_sync_queue set last_sync_time = ? where site_id = ?")
                    .param(timestamp)
                    .param(entry.siteId)
                    .executeUpdate();

                if (updates == 0) {
                    db.run("insert into seat_sync_queue (site_id, last_sync_time, last_sync_requested_time) values (?, ?, ?)")
                        .param(entry.siteId)
                        .param(timestamp)
                        .param(timestamp)
                        .executeUpdate();
                }

                recentProcessed.put(entry.siteId, entry.lastSyncRequestTime);

                db.commit();
                return null;
            });
    }

    public static long handleSeatGroupUpdates(long findProcessedSince, BrightspaceClient brightspace) {
        long now = System.currentTimeMillis();
        List<ToProcess> sites = findSitesToProcess(findProcessedSince);

        for (ToProcess entry : sites) {
            long startTime = System.currentTimeMillis();

            if (processSite(entry.siteId, brightspace)) {
                markAsProcessed(entry, now);
                LOG.info(String.format("Processed site %s in %d ms", entry.siteId, (System.currentTimeMillis() - startTime)));
            }
        }

        return now - WINDOW_MS;
    }


    private static boolean processSite(String siteId, BrightspaceClient brightspace) {
        try {
            if (!Locks.trylockSiteForUpdate(siteId)) {
                // Currently locked.  Skip processing and try again later.
                LOG.info(String.format("Site %s already locked for update.  Skipping...", siteId));

                return false;
            }

            try {
                LOG.info("Process: " + siteId);

                boolean performDelete = "true".equals(HotReloadConfigurationService.getString("seats.lti.auto-delete", "false"));


                DB.transaction
                    ("Bootstrap groups for a site and section",
                     (DBConnection db) -> {
                        try {
                            BrightspaceSectionInfo sectionInfo = brightspace.getSectionInfo(db, siteId);
                            BrightspaceClient.CourseOfferingData courseData = brightspace.fetchCourseData(siteId);

                            // Find any cohorts that linked to a detached roster
                            db.run("select sec.primary_stem_name, sec.site_id, sec.id as section_id" +
                                   " from SEAT_GROUP_SECTION sec" +
                                   " where sec.site_id = ?")
                                .param(siteId)
                                .executeQuery()
                                .each((row) -> {
                                        if (sectionInfo.getSection(row.getString("primary_stem_name")) == null) {
                                            // This section doesn't appear in Brightspace anymore

                                            LOG.info(String.format("Removing Seats section for detached roster '%s' in site '%s'",
                                                                   row.getString("primary_stem_name"),
                                                                   row.getString("site_id")));

                                            SeatsStorage.getSeatSection(db, row.getString("section_id"), row.getString("site_id"))
                                                .ifPresent((section) -> {
                                                        try {
                                                            if (performDelete) {
                                                                SeatsStorage.deleteSection(db, section);
                                                            } else {
                                                                LOG.error("Delete skipped due to seats.lti.auto-delete=false");
                                                            }
                                                        } catch (SQLException e) {
                                                            LOG.error("Failure during delete: " + e);
                                                            e.printStackTrace();
                                                        }
                                                    });
                                        }
                                    });


                            // Sync the rosters
                            for (BrightspaceSection brightspaceSection : sectionInfo.getSections()) {
                                String rosterId = brightspaceSection.getRosterId();
                                String sponsorStemName = SeatsStorage.getSponsorSectionId(db, rosterId);

                                if (!SeatsStorage.stemIsEligibleInstructionMode(db, sponsorStemName)) {
                                    continue;
                                }

                                if (Utils.rosterToStemName(rosterId).equals(sponsorStemName)) {
                                    SeatsStorage.ensureRosterEntry(db, siteId, sponsorStemName, Optional.empty());
                                } else {
                                    SeatsStorage.ensureRosterEntry(db, siteId, sponsorStemName, Optional.of(rosterId));
                                }
                            }


                            for (SeatSection section : SeatsStorage.siteSeatSections(db, siteId)) {
                                if (section.provisioned) {
                                    SeatsStorage.SyncResult syncResult = BrightspaceSeatsStorage.syncGroupsToSection(db, section, sectionInfo);

                                    if (section.listGroups().size() > 1 && courseData.isPublished) {
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
                                                         notifyUser(m.netid, courseData.title, group.get(), sectionInfo);
                                                    } catch (Exception e) {
                                                        LOG.error(String.format("Failure while notifying user '%s' in group '%s' for site '%s': %s",
                                                                                m.netid,
                                                                                group.get().id,
                                                                                siteId,
                                                                                e));
                                                        e.printStackTrace();
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    List<Member> sectionMembers = BrightspaceSeatsStorage.getMembersForSectionFromInfo(db, section, sectionInfo);
                                    SeatsStorage.bootstrapGroupsForSection(db, section,  sectionMembers, 1, SeatsStorage.SelectionType.RANDOM);
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
            } catch (Exception e) {
                LOG.error(String.format("Error while processing site '%s': ", siteId) + e);
                e.printStackTrace();
                return true;
            }
        } finally {
            Locks.unlockSiteForUpdate(siteId);
        }
    }

    private static void notifyUser(String studentNetId, String siteTitle, SeatGroup group, BrightspaceSectionInfo sectionInfo) throws Exception {
        List<String> managerNetIds = sectionInfo.listAllMembers().stream().filter(m -> m.isManager()).map(m -> m.netid).collect(Collectors.toList());
        Map<String, SeatsStorage.UserDisplayName> managerNames = BrightspaceSeatsStorage.getMemberNames(managerNetIds);
        List<BrightspaceEmails.EmailAddress> ccEmails = managerNames.values().stream().map(data -> BrightspaceEmails.makeEmailAddress(data.displayName, data.email)).collect(Collectors.toList());

        SeatsStorage.UserDisplayName studentDisplayName = BrightspaceSeatsStorage.getMemberNames(Arrays.asList(studentNetId)).get(studentNetId);
        BrightspaceEmails.EmailAddress studentEmail = null;
        if (studentDisplayName == null) {
            LOG.warn("WARNING: User lookup failed for netid: " + studentNetId);
            studentEmail = BrightspaceEmails.makeEmailAddress(studentNetId, String.format("%s@nyu.edu", studentNetId));
        } else {
            studentEmail = BrightspaceEmails.makeEmailAddress(studentDisplayName.displayName, studentDisplayName.email);
        }

        final BrightspaceEmails.EmailAddress finalStudentEmail = studentEmail;

        emailPool.submit(() -> {
            try {
                BrightspaceEmails.sendUserAddedEmail(finalStudentEmail, ccEmails, group, siteTitle, sectionInfo);
            } catch (Exception e) {
                LOG.error(String.format("Failed to send email to student: %s for section: %s", studentNetId, group.section.id), e);
                throw new RuntimeException(e);
            }
        });
    }

}
