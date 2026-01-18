/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2025 Nextcloud GmbH
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */

package com.nextcloud.client.jobs.upload

import android.app.NotificationManager
import android.content.Context
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.nextcloud.android.common.ui.theme.MaterialSchemes
import com.nextcloud.client.account.User
import com.nextcloud.client.account.UserAccountManager
import com.nextcloud.client.device.PowerManagementService
import com.nextcloud.client.jobs.BackgroundJobManager
import com.nextcloud.client.network.ClientFactory
import com.nextcloud.client.network.Connectivity
import com.nextcloud.client.network.ConnectivityService
import com.nextcloud.client.preferences.AppPreferences
import com.owncloud.android.datamodel.UploadsStorageManager
import com.owncloud.android.db.OCUpload
import com.owncloud.android.lib.common.OwnCloudClient
import com.owncloud.android.lib.common.operations.RemoteOperationResult
import com.owncloud.android.lib.common.operations.RemoteOperationResult.ResultCode
import com.owncloud.android.operations.UploadFileOperation
import com.owncloud.android.utils.theme.ViewThemeUtils
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Optional

class FileUploadWorkerTest {

    private lateinit var worker: FileUploadWorker
    private val uploadsStorageManager: UploadsStorageManager = mockk(relaxed = true)
    private val connectivityService: ConnectivityService = mockk(relaxed = true)
    private val powerManagementService: PowerManagementService = mockk(relaxed = true)
    private val userAccountManager: UserAccountManager = mockk(relaxed = true)
    private val localBroadcastManager: LocalBroadcastManager = mockk(relaxed = true)
    private val backgroundJobManager: BackgroundJobManager = mockk(relaxed = true)
    private val preferences: AppPreferences = mockk(relaxed = true)
    private val uploadFileOperationFactory: FileUploadOperationFactory = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)
    private val params: WorkerParameters = mockk(relaxed = true)
    private val systemNotificationManager: NotificationManager = mockk(relaxed = true)
    private val uploadNotificationManager: UploadNotificationManager = mockk(relaxed = true)

    @Before
    fun setUp() {
        every { context.getSystemService(Context.NOTIFICATION_SERVICE) } returns systemNotificationManager
        
        val materialSchemes = mockk<MaterialSchemes>(relaxed = true)
        val viewThemeUtils = ViewThemeUtils(materialSchemes, mockk(relaxed = true))

        val connectivity = mockk<Connectivity>()
        every { connectivity.isConnected } returns true
        every { connectivityService.getConnectivity() } returns connectivity
        every { connectivityService.isConnected } returns true
        every { connectivityService.isInternetWalled } returns false

        worker = FileUploadWorker(
            uploadsStorageManager,
            connectivityService,
            powerManagementService,
            userAccountManager,
            viewThemeUtils,
            localBroadcastManager,
            backgroundJobManager,
            preferences,
            uploadFileOperationFactory,
            context,
            uploadNotificationManager,
            params
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
        FileUploadWorker.activeUploadFileOperations.clear()
    }

    @Test
    fun `doWork returns failure when account name is missing`() = runBlocking {
        // GIVEN
        every { params.inputData.getString(FileUploadWorker.ACCOUNT) } returns null

        // WHEN
        val result = worker.doWork()

        // THEN
        assertEquals(ListenableWorker.Result.failure(), result)
    }

    @Test
    fun `doWork returns success when there are no uploads`() = runBlocking {
        // GIVEN
        val accountName = "account"
        val user = mockk<User>(relaxed = true)
        every { params.inputData.getString(FileUploadWorker.ACCOUNT) } returns accountName
        every { params.inputData.getLongArray(FileUploadWorker.UPLOAD_IDS) } returns longArrayOf(1L)
        every { params.inputData.getInt(FileUploadWorker.CURRENT_BATCH_INDEX, any()) } returns 0
        every { params.inputData.getInt(FileUploadWorker.TOTAL_UPLOAD_SIZE, any()) } returns 1
        every { userAccountManager.getUser(accountName) } returns Optional.of(user)
        every { uploadsStorageManager.getUploadsByIds(any(), accountName) } returns emptyList()

        // WHEN
        val result = worker.doWork()

        // THEN
        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun `doWork skips uploads when globally paused`() = runBlocking {
        // GIVEN
        val accountName = "account"
        val user = mockk<User>(relaxed = true)
        val upload = mockk<OCUpload>(relaxed = true)
        every { params.inputData.getString(FileUploadWorker.ACCOUNT) } returns accountName
        every { params.inputData.getLongArray(FileUploadWorker.UPLOAD_IDS) } returns longArrayOf(1L)
        every { params.inputData.getInt(FileUploadWorker.CURRENT_BATCH_INDEX, any()) } returns 0
        every { params.inputData.getInt(FileUploadWorker.TOTAL_UPLOAD_SIZE, any()) } returns 1
        every { userAccountManager.getUser(accountName) } returns Optional.of(user)
        every { uploadsStorageManager.getUploadsByIds(any(), accountName) } returns listOf(upload)
        every { preferences.isGlobalUploadPaused } returns true

        // WHEN
        val result = worker.doWork()

        // THEN
        assertEquals(ListenableWorker.Result.success(), result)
        verify(exactly = 0) { uploadFileOperationFactory.create(any(), any(), any()) }
    }

    @Test
    fun `doWork returns failure when quota is exceeded`() = runBlocking {
        // GIVEN
        val accountName = "account"
        val user = mockk<User>(relaxed = true)
        val upload = mockk<OCUpload>(relaxed = true)
        val client = mockk<OwnCloudClient>(relaxed = true)
        val operation = mockk<UploadFileOperation>(relaxed = true)
        val quotaResult = RemoteOperationResult<Any?>(ResultCode.QUOTA_EXCEEDED)

        every { params.inputData.getString(FileUploadWorker.ACCOUNT) } returns accountName
        every { params.inputData.getLongArray(FileUploadWorker.UPLOAD_IDS) } returns longArrayOf(1L)
        every { params.inputData.getInt(FileUploadWorker.CURRENT_BATCH_INDEX, any()) } returns 0
        every { params.inputData.getInt(FileUploadWorker.TOTAL_UPLOAD_SIZE, any()) } returns 1
        every { userAccountManager.getUser(accountName) } returns Optional.of(user)
        every { uploadsStorageManager.getUploadsByIds(any(), accountName) } returns listOf(upload)
        every { preferences.isGlobalUploadPaused } returns false
        every { uploadFileOperationFactory.create(upload, user, any()) } returns operation
        coEvery { operation.execute(client) } returns quotaResult

        // WHEN
        val result = worker.doWork()

        // THEN
        assertEquals(ListenableWorker.Result.failure(), result)
    }
}
