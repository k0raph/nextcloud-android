/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2025 Nextcloud GmbH
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */
package com.nextcloud.client.jobs.upload

import androidx.work.WorkManager
import com.owncloud.android.AbstractOnServerIT
import com.owncloud.android.lib.resources.files.ReadFileRemoteOperation
import com.owncloud.android.lib.resources.files.model.RemoteFile
import com.owncloud.android.operations.UploadFileOperation
import com.owncloud.android.files.services.NameCollisionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FileUploadWorkerIT : AbstractOnServerIT() {

    private lateinit var workManager: WorkManager
    private lateinit var fileUploadHelper: FileUploadHelper

    @Before
    fun setUp() {
        workManager = WorkManager.getInstance(targetContext)
        fileUploadHelper = FileUploadHelper.instance()
    }

    @Test
    fun testFileUploadWorkerSuccess() {
        // GIVEN a file to upload
        val file = getDummyFile("nonEmpty.txt")
        val remotePath = "/test_upload_worker_${System.currentTimeMillis()}.txt"

        // WHEN starting an upload through the helper (which schedules FileUploadWorker)
        fileUploadHelper.uploadNewFiles(
            user,
            arrayOf(file.absolutePath),
            arrayOf(remotePath),
            FileUploadWorker.LOCAL_BEHAVIOUR_COPY,
            true,
            UploadFileOperation.CREATED_BY_USER,
            false,
            false,
            NameCollisionPolicy.DEFAULT
        )

        // THEN wait for the job to finish
        // The tag used in BackgroundJobManagerImpl for file uploads is "files_upload" + accountName
        val tag = "files_upload" + user.accountName
        
        var isFinished = false
        val startTime = System.currentTimeMillis()
        val timeout = 60000L // 60 seconds timeout for integration test

        while (!isFinished && System.currentTimeMillis() - startTime < timeout) {
            val workInfos = workManager.getWorkInfosByTag(tag).get()
            if (workInfos.isNotEmpty() && workInfos.all { it.state.isFinished }) {
                isFinished = true
            } else {
                Thread.sleep(1000)
            }
        }

        assertTrue("Upload job did not finish within timeout", isFinished)

        // AND verify the file exists on the server with correct size
        val result = ReadFileRemoteOperation(remotePath).execute(client)
        assertTrue("File should be readable on server: ${result.logMessage}", result.isSuccess)
        val remoteFile = result.data[0] as RemoteFile
        assertEquals("Remote file size should match local file size", file.length(), remoteFile.length)
    }

    @Test
    fun testMultipleFilesUploadBatch() {
        // GIVEN multiple files to upload
        val file1 = getDummyFile("empty.txt")
        val file2 = getDummyFile("nonEmpty.txt")
        val remotePath1 = "/batch_upload_1_${System.currentTimeMillis()}.txt"
        val remotePath2 = "/batch_upload_2_${System.currentTimeMillis()}.txt"

        // WHEN starting a batch upload
        fileUploadHelper.uploadNewFiles(
            user,
            arrayOf(file1.absolutePath, file2.absolutePath),
            arrayOf(remotePath1, remotePath2),
            FileUploadWorker.LOCAL_BEHAVIOUR_COPY,
            true,
            UploadFileOperation.CREATED_BY_USER,
            false,
            false,
            NameCollisionPolicy.DEFAULT
        )

        // THEN wait for the jobs to finish
        val tag = "files_upload" + user.accountName
        var isFinished = false
        val startTime = System.currentTimeMillis()
        val timeout = 90000L // Longer timeout for batch

        while (!isFinished && System.currentTimeMillis() - startTime < timeout) {
            val workInfos = workManager.getWorkInfosByTag(tag).get()
            if (workInfos.isNotEmpty() && workInfos.all { it.state.isFinished }) {
                isFinished = true
            } else {
                Thread.sleep(1000)
            }
        }

        assertTrue("Batch upload jobs did not finish within timeout", isFinished)

        // AND verify both files on server
        val result1 = ReadFileRemoteOperation(remotePath1).execute(client)
        assertTrue("File 1 should be on server", result1.isSuccess)
        
        val result2 = ReadFileRemoteOperation(remotePath2).execute(client)
        assertTrue("File 2 should be on server", result2.isSuccess)
    }
}
