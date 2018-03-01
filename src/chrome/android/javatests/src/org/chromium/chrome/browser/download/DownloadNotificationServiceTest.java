// Copyright 2015 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.download;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.support.test.filters.SmallTest;
import android.test.ServiceTestCase;

import org.chromium.base.ContextUtils;
import org.chromium.base.ThreadUtils;
import org.chromium.base.test.util.AdvancedMockContext;
import org.chromium.base.test.util.Feature;
import org.chromium.base.test.util.RetryOnFailure;
import org.chromium.components.offline_items_collection.ContentId;
import org.chromium.components.offline_items_collection.LegacyHelpers;
import org.chromium.components.offline_items_collection.OfflineItem.Progress;
import org.chromium.components.offline_items_collection.OfflineItemProgressUnit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Tests of {@link DownloadNotificationService}.
 */
public class DownloadNotificationServiceTest extends
        ServiceTestCase<MockDownloadNotificationService> {

    private static class MockDownloadManagerService extends DownloadManagerService {
        final List<DownloadItem> mDownloads = new ArrayList<DownloadItem>();

        public MockDownloadManagerService(Context context) {
            super(context, null, getTestHandler(), 1000);
        }

        @Override
        protected void init() {}

        @Override
        public void resumeDownload(ContentId id, DownloadItem item, boolean hasUserGesture) {
            mDownloads.add(item);
        }
    }

    private static class MockDownloadResumptionScheduler extends DownloadResumptionScheduler {
        boolean mScheduled;

        public MockDownloadResumptionScheduler(Context context) {
            super(context);
        }

        @Override
        public void schedule(boolean allowMeteredConnection) {
            mScheduled = true;
        }

        @Override
        public void cancelTask() {
            mScheduled = false;
        }
    }

    private static String buildEntryStringWithGuid(String guid, int notificationId, String fileName,
            boolean metered, boolean autoResume, boolean offTheRecord) {
        return new DownloadSharedPreferenceEntry(LegacyHelpers.buildLegacyContentId(false, guid),
                notificationId, offTheRecord, metered, fileName, autoResume, false)
                .getSharedPreferenceString();
    }

    private static String buildEntryStringWithGuid(
            String guid, int notificationId, String fileName, boolean metered, boolean autoResume) {
        return buildEntryStringWithGuid(guid, notificationId, fileName, metered, autoResume, false);
    }

    private static String buildEntryString(
            int notificationId, String fileName, boolean metered, boolean autoResume) {
        return buildEntryStringWithGuid(
                UUID.randomUUID().toString(), notificationId, fileName, metered, autoResume);
    }

    public DownloadNotificationServiceTest() {
        super(MockDownloadNotificationService.class);
    }

    @Override
    protected void setupService() {
        super.setupService();
    }

    @Override
    protected void shutdownService() {
        ThreadUtils.runOnUiThreadBlocking(new Runnable() {
            @Override
            public void run() {
                DownloadNotificationServiceTest.super.shutdownService();
            }
        });
    }

    @Override
    protected void tearDown() throws Exception {
        SharedPreferences sharedPrefs = ContextUtils.getAppSharedPreferences();
        SharedPreferences.Editor editor = sharedPrefs.edit();
        editor.remove(DownloadSharedPreferenceHelper.KEY_PENDING_DOWNLOAD_NOTIFICATIONS);
        editor.apply();
        super.tearDown();
    }

    private void startNotificationService() {
        ThreadUtils.runOnUiThreadBlocking(new Runnable() {
            @Override
            public void run() {
                Intent intent = new Intent(getService(), MockDownloadNotificationService.class);
                startService(intent);
            }
        });
    }

    private DownloadNotificationService bindNotificationService() {
        Intent intent = new Intent(getService(), MockDownloadNotificationService.class);
        IBinder service = bindService(intent);
        return ((DownloadNotificationService.LocalBinder) service).getService();
    }

    private static Handler getTestHandler() {
        HandlerThread handlerThread = new HandlerThread("handlerThread");
        handlerThread.start();
        return new Handler(handlerThread.getLooper());
    }

    private void resumeAllDownloads(final DownloadNotificationService service) throws Exception {
        ThreadUtils.runOnUiThreadBlocking(new Runnable() {
            @Override
            public void run() {
                service.resumeAllPendingDownloads();
            }
        });
    }

    /**
     * Tests that creating the service without launching chrome will do nothing if there is no
     * ongoing download.
     */
    @SmallTest
    @Feature({"Download"})
    public void testPausingWithoutOngoingDownloads() {
        setupService();
        startNotificationService();
        ThreadUtils.runOnUiThreadBlocking(new Runnable() {
            @Override
            public void run() {
                getService().updateNotificationsForShutdown();
            }
        });
        assertTrue(getService().isPaused());
        assertTrue(getService().getNotificationIds().isEmpty());
    }

    /**
     * Tests that download resumption task is scheduled when notification service is started
     * without any download action.
     */
    @SmallTest
    @Feature({"Download"})
    public void testResumptionScheduledWithoutDownloadOperationIntent() throws Exception {
        MockDownloadResumptionScheduler scheduler = new MockDownloadResumptionScheduler(
                getSystemContext().getApplicationContext());
        DownloadResumptionScheduler.setDownloadResumptionScheduler(scheduler);
        setupService();
        Set<String> notifications = new HashSet<>();
        notifications.add(buildEntryString(1, "test1", true, true));
        SharedPreferences sharedPrefs = ContextUtils.getAppSharedPreferences();
        SharedPreferences.Editor editor = sharedPrefs.edit();
        editor.putStringSet(
                DownloadSharedPreferenceHelper.KEY_PENDING_DOWNLOAD_NOTIFICATIONS, notifications);
        editor.apply();
        startNotificationService();
        shutdownService();
        assertTrue(scheduler.mScheduled);
    }

    /**
     * Tests that download resumption task is not scheduled when notification service is started
     * with a download action.
     */
    @SmallTest
    @Feature({"Download"})
    public void testResumptionNotScheduledWithDownloadOperationIntent() {
        MockDownloadResumptionScheduler scheduler = new MockDownloadResumptionScheduler(
                getSystemContext().getApplicationContext());
        DownloadResumptionScheduler.setDownloadResumptionScheduler(scheduler);
        setupService();
        ThreadUtils.runOnUiThreadBlocking(new Runnable() {
            @Override
            public void run() {
                Intent intent = new Intent(getService(), MockDownloadNotificationService.class);
                intent.setAction(DownloadNotificationService.ACTION_DOWNLOAD_RESUME_ALL);
                startService(intent);
            }
        });
        assertFalse(scheduler.mScheduled);
    }

    /**
     * Tests that download resumption task is not scheduled when there is no auto resumable
     * download in SharedPreferences.
     */
    @SmallTest
    @Feature({"Download"})
    public void testResumptionNotScheduledWithoutAutoResumableDownload() throws Exception {
        MockDownloadResumptionScheduler scheduler = new MockDownloadResumptionScheduler(
                getSystemContext().getApplicationContext());
        DownloadResumptionScheduler.setDownloadResumptionScheduler(scheduler);
        setupService();
        Set<String> notifications = new HashSet<>();
        notifications.add(buildEntryString(1, "test1", true, false));
        SharedPreferences sharedPrefs = ContextUtils.getAppSharedPreferences();
        SharedPreferences.Editor editor = sharedPrefs.edit();
        editor.putStringSet(
                DownloadSharedPreferenceHelper.KEY_PENDING_DOWNLOAD_NOTIFICATIONS, notifications);
        editor.apply();
        startNotificationService();
        assertFalse(scheduler.mScheduled);
    }

    /**
     * Tests that creating the service without launching chrome will pause all ongoing downloads.
     */
    @SmallTest
    @Feature({"Download"})
    public void testPausingWithOngoingDownloads() {
        setupService();
        Context mockContext = new AdvancedMockContext(getSystemContext());
        getService().setContext(mockContext);
        Set<String> notifications = new HashSet<>();
        notifications.add(buildEntryString(1, "test1", true, true));
        notifications.add(buildEntryString(2, "test2", true, true));
        SharedPreferences sharedPrefs =
                ContextUtils.getAppSharedPreferences();
        SharedPreferences.Editor editor = sharedPrefs.edit();
        editor.putStringSet(
                DownloadSharedPreferenceHelper.KEY_PENDING_DOWNLOAD_NOTIFICATIONS, notifications);
        editor.apply();
        startNotificationService();
        ThreadUtils.runOnUiThreadBlocking(new Runnable() {
            @Override
            public void run() {
                getService().updateNotificationsForShutdown();
            }
        });
        assertTrue(getService().isPaused());
        assertEquals(2, getService().getNotificationIds().size());
        assertTrue(getService().getNotificationIds().contains(1));
        assertTrue(getService().getNotificationIds().contains(2));
        assertTrue(sharedPrefs.contains(
                DownloadSharedPreferenceHelper.KEY_PENDING_DOWNLOAD_NOTIFICATIONS));
    }

    /**
     * Tests adding and cancelling notifications.
     */
    @SmallTest
    @Feature({"Download"})
    public void testAddingAndCancelingNotifications() {
        setupService();
        Context mockContext = new AdvancedMockContext(getSystemContext());
        getService().setContext(mockContext);
        Set<String> notifications = new HashSet<>();
        String guid1 = UUID.randomUUID().toString();
        String guid2 = UUID.randomUUID().toString();
        notifications.add(buildEntryStringWithGuid(guid1, 3, "success", true, true));
        notifications.add(buildEntryStringWithGuid(guid2, 4, "failed", true, true));
        SharedPreferences sharedPrefs = ContextUtils.getAppSharedPreferences();
        SharedPreferences.Editor editor = sharedPrefs.edit();
        editor.putStringSet(
                DownloadSharedPreferenceHelper.KEY_PENDING_DOWNLOAD_NOTIFICATIONS, notifications);
        editor.apply();
        startNotificationService();
        ThreadUtils.runOnUiThreadBlocking(new Runnable() {
            @Override
            public void run() {
                getService().updateNotificationsForShutdown();
            }
        });
        assertEquals(2, getService().getNotificationIds().size());
        assertTrue(getService().getNotificationIds().contains(3));
        assertTrue(getService().getNotificationIds().contains(4));

        DownloadNotificationService service = bindNotificationService();
        ContentId id3 = LegacyHelpers.buildLegacyContentId(false, UUID.randomUUID().toString());
        service.notifyDownloadProgress(id3, "test",
                new Progress(1, 100L, OfflineItemProgressUnit.PERCENTAGE), 100L, 1L, 1L, true, true,
                false, null);
        assertEquals(3, getService().getNotificationIds().size());
        int lastNotificationId = getService().getLastAddedNotificationId();
        Set<String> entries = DownloadManagerService.getStoredDownloadInfo(
                sharedPrefs, DownloadSharedPreferenceHelper.KEY_PENDING_DOWNLOAD_NOTIFICATIONS);
        assertEquals(3, entries.size());

        ContentId id1 = LegacyHelpers.buildLegacyContentId(false, guid1);
        service.notifyDownloadSuccessful(
                id1, "/path/to/success", "success", 100L, false, false, true, null);
        entries = DownloadManagerService.getStoredDownloadInfo(
                sharedPrefs, DownloadSharedPreferenceHelper.KEY_PENDING_DOWNLOAD_NOTIFICATIONS);
        assertEquals(2, entries.size());

        ContentId id2 = LegacyHelpers.buildLegacyContentId(false, guid2);
        service.notifyDownloadFailed(id2, "failed", null);
        entries = DownloadManagerService.getStoredDownloadInfo(
                sharedPrefs, DownloadSharedPreferenceHelper.KEY_PENDING_DOWNLOAD_NOTIFICATIONS);
        assertEquals(1, entries.size());

        service.notifyDownloadCanceled(id3);
        assertEquals(2, getService().getNotificationIds().size());
        assertFalse(getService().getNotificationIds().contains(lastNotificationId));

        ContentId id4 = LegacyHelpers.buildLegacyContentId(false, UUID.randomUUID().toString());
        service.notifyDownloadSuccessful(
                id4, "/path/to/success", "success", 100L, false, false, true, null);
        assertEquals(3, getService().getNotificationIds().size());
        int nextNotificationId = getService().getLastAddedNotificationId();
        service.cancelNotification(nextNotificationId, id4);
        assertEquals(2, getService().getNotificationIds().size());
        assertFalse(getService().getNotificationIds().contains(nextNotificationId));
    }

    /**
     * Tests that notification is updated if download success comes without any prior progress.
     */
    @SmallTest
    @Feature({"Download"})
    @RetryOnFailure
    public void testDownloadSuccessNotification() {
        setupService();
        startNotificationService();
        DownloadNotificationService service = bindNotificationService();
        ContentId id = LegacyHelpers.buildLegacyContentId(false, UUID.randomUUID().toString());
        service.notifyDownloadSuccessful(
                id, "/path/to/test", "test", 100L, false, false, true, null);
        assertEquals(1, getService().getNotificationIds().size());
    }

    /**
     * Tests resume all pending downloads. Only auto resumable downloads can resume.
     */
    @SmallTest
    @Feature({"Download"})
    @RetryOnFailure
    public void testResumeAllPendingDownloads() throws Exception {
        setupService();
        Context mockContext = new AdvancedMockContext(getSystemContext());
        getService().setContext(mockContext);
        Set<String> notifications = new HashSet<>();
        String guid1 = UUID.randomUUID().toString();
        String guid2 = UUID.randomUUID().toString();
        String guid3 = UUID.randomUUID().toString();

        notifications.add(buildEntryStringWithGuid(guid1, 3, "success", false, true));
        notifications.add(buildEntryStringWithGuid(guid2, 4, "failed", true, true));
        notifications.add(buildEntryStringWithGuid(guid3, 5, "nonresumable", true, false));

        SharedPreferences sharedPrefs = ContextUtils.getAppSharedPreferences();
        SharedPreferences.Editor editor = sharedPrefs.edit();
        editor.putStringSet(
                DownloadSharedPreferenceHelper.KEY_PENDING_DOWNLOAD_NOTIFICATIONS, notifications);
        editor.apply();
        startNotificationService();
        DownloadNotificationService service = bindNotificationService();
        DownloadManagerService.disableNetworkListenerForTest();

        final MockDownloadManagerService manager =
                new MockDownloadManagerService(getSystemContext().getApplicationContext());
        ThreadUtils.runOnUiThreadBlocking(new Runnable() {
            @Override
            public void run() {
                DownloadManagerService.setDownloadManagerService(manager);
            }
        });
        DownloadManagerService.setIsNetworkMeteredForTest(true);
        resumeAllDownloads(service);
        assertEquals(1, manager.mDownloads.size());
        assertEquals(manager.mDownloads.get(0).getDownloadInfo().getDownloadGuid(), guid2);

        manager.mDownloads.clear();
        DownloadManagerService.setIsNetworkMeteredForTest(false);
        resumeAllDownloads(service);
        assertEquals(1, manager.mDownloads.size());
        assertEquals(manager.mDownloads.get(0).getDownloadInfo().getDownloadGuid(), guid1);
    }

    /**
     * Tests incognito download fails when browser gets killed.
     */
    @SmallTest
    @Feature({"Download"})
    public void testIncognitoDownloadCanceledOnServiceShutdown() throws Exception {
        setupService();
        Context mockContext = new AdvancedMockContext(getSystemContext());
        getService().setContext(mockContext);
        Set<String> notifications = new HashSet<>();
        String uuid = UUID.randomUUID().toString();
        notifications.add(buildEntryStringWithGuid(uuid, 1, "test1", true, true, true));
        SharedPreferences sharedPrefs =
                ContextUtils.getAppSharedPreferences();
        SharedPreferences.Editor editor = sharedPrefs.edit();
        editor.putStringSet(
                DownloadSharedPreferenceHelper.KEY_PENDING_DOWNLOAD_NOTIFICATIONS, notifications);
        editor.apply();
        startNotificationService();

        ThreadUtils.runOnUiThreadBlocking(new Runnable() {
            @Override
            public void run() {
                getService().onTaskRemoved(new Intent());
            }
        });

        assertTrue(getService().isPaused());
        assertFalse(sharedPrefs.contains(
                DownloadSharedPreferenceHelper.KEY_PENDING_DOWNLOAD_NOTIFICATIONS));
    }

    @SmallTest
    @Feature({"Download"})
    public void testServiceWillStopOnCompletedDownload() throws Exception {
        // On versions of Android that use a foreground service, the service will currently die with
        // the notifications.
        if (DownloadNotificationService.useForegroundService()) return;

        setupService();
        startNotificationService();
        DownloadNotificationService service = bindNotificationService();
        ContentId id = LegacyHelpers.buildLegacyContentId(false, UUID.randomUUID().toString());
        service.notifyDownloadProgress(id, "/path/to/test", Progress.createIndeterminateProgress(),
                10L, 1000L, 10L, false, false, false, null);
        assertFalse(service.hideSummaryNotificationIfNecessary(-1));
        service.notifyDownloadSuccessful(
                id, "/path/to/test", "test", 100L, false, false, true, null);
        assertTrue(service.hideSummaryNotificationIfNecessary(-1));
    }

    @SmallTest
    @Feature({"Download"})
    public void testServiceWillStopOnFailedDownload() throws Exception {
        // On versions of Android that use a foreground service, the service will currently die with
        // the notifications.
        if (DownloadNotificationService.useForegroundService()) return;

        setupService();
        startNotificationService();
        DownloadNotificationService service = bindNotificationService();
        ContentId id = LegacyHelpers.buildLegacyContentId(false, UUID.randomUUID().toString());
        service.notifyDownloadProgress(id, "/path/to/test", Progress.createIndeterminateProgress(),
                10L, 1000L, 10L, false, false, false, null);
        assertFalse(service.hideSummaryNotificationIfNecessary(-1));
        service.notifyDownloadFailed(id, "/path/to/test", null);
        assertTrue(service.hideSummaryNotificationIfNecessary(-1));
    }

    @SmallTest
    @Feature({"Download"})
    public void testServiceWillStopOnCancelledDownload() throws Exception {
        // On versions of Android that use a foreground service, the service will currently die with
        // the notifications.
        if (DownloadNotificationService.useForegroundService()) return;

        setupService();
        startNotificationService();
        DownloadNotificationService service = bindNotificationService();
        ContentId id = LegacyHelpers.buildLegacyContentId(false, UUID.randomUUID().toString());
        service.notifyDownloadProgress(id, "/path/to/test", Progress.createIndeterminateProgress(),
                10L, 1000L, 10L, false, false, false, null);
        assertFalse(service.hideSummaryNotificationIfNecessary(-1));
        service.notifyDownloadCanceled(id);
        assertTrue(service.hideSummaryNotificationIfNecessary(-1));
    }

    @SmallTest
    @Feature({"Download"})
    public void testServiceWillNotStopOnInterruptedDownload() throws Exception {
        // On versions of Android that use a foreground service, the service will currently die with
        // the notifications.
        if (DownloadNotificationService.useForegroundService()) return;

        setupService();
        startNotificationService();
        DownloadNotificationService service = bindNotificationService();
        ContentId id = LegacyHelpers.buildLegacyContentId(false, UUID.randomUUID().toString());
        service.notifyDownloadProgress(id, "/path/to/test", Progress.createIndeterminateProgress(),
                10L, 1000L, 10L, false, false, false, null);
        assertFalse(service.hideSummaryNotificationIfNecessary(-1));
        service.notifyDownloadPaused(id, "/path/to/test", true, true, false, false, null);
        assertFalse(service.hideSummaryNotificationIfNecessary(-1));
    }

    @SmallTest
    @Feature({"Download"})
    public void testServiceWillNotStopOnPausedDownload() throws Exception {
        // On versions of Android that use a foreground service, the service will currently die with
        // the notifications.
        if (DownloadNotificationService.useForegroundService()) return;

        setupService();
        startNotificationService();
        DownloadNotificationService service = bindNotificationService();
        ContentId id = LegacyHelpers.buildLegacyContentId(false, UUID.randomUUID().toString());
        service.notifyDownloadProgress(id, "/path/to/test", Progress.createIndeterminateProgress(),
                10L, 1000L, 10L, false, false, false, null);
        assertFalse(service.hideSummaryNotificationIfNecessary(-1));
        service.notifyDownloadPaused(id, "/path/to/test", true, false, false, false, null);
        assertFalse(service.hideSummaryNotificationIfNecessary(-1));
    }

    @SmallTest
    @Feature({"Download"})
    public void testServiceWillNotStopWithOneOngoingDownload() throws Exception {
        // On versions of Android that use a foreground service, the service will currently die with
        // the notifications.
        if (DownloadNotificationService.useForegroundService()) return;

        setupService();
        startNotificationService();
        DownloadNotificationService service = bindNotificationService();
        ContentId id1 = LegacyHelpers.buildLegacyContentId(false, UUID.randomUUID().toString());
        ContentId id2 = LegacyHelpers.buildLegacyContentId(false, UUID.randomUUID().toString());

        service.notifyDownloadProgress(id1, "/path/to/test", Progress.createIndeterminateProgress(),
                10L, 1000L, 10L, false, false, false, null);
        service.notifyDownloadProgress(id2, "/path/to/test", Progress.createIndeterminateProgress(),
                10L, 1000L, 10L, false, false, false, null);
        assertFalse(service.hideSummaryNotificationIfNecessary(-1));
        service.notifyDownloadPaused(id1, "/path/to/test", true, false, false, false, null);
        assertFalse(service.hideSummaryNotificationIfNecessary(-1));
        service.notifyDownloadSuccessful(
                id1, "/path/to/test", "test", 100L, false, false, true, null);
        assertFalse(service.hideSummaryNotificationIfNecessary(-1));
        service.notifyDownloadSuccessful(
                id2, "/path/to/test", "test", 100L, false, false, true, null);
        assertTrue(service.hideSummaryNotificationIfNecessary(-1));
    }
}