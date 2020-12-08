package com.betomorrow.gradle.appcenter.infra

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import retrofit2.Response
import java.io.File

private const val CONTENT_TYPE_APK = "application/vnd.android.package-archive"

private const val BACKOFF_DELAY = 1_000L
private const val MAX_RETRIES = 60

class AppCenterUploader(
    private val uploadApiFactory: (String) -> UploadAPI,
    private val apiClient: AppCenterAPI,
    private val okHttpClient: OkHttpClient,
    private val ownerName: String,
    private val appName: String
) {

    fun uploadApk(file: File, changeLog: String, destinationNames: List<String>, notifyTesters: Boolean) {
        uploadApk(file, changeLog, destinationNames, notifyTesters) { }
    }

    fun uploadApk(file: File, changeLog: String, destinationNames: List<String>, notifyTesters: Boolean, logger: (String) -> Unit) {
        logger("Step 1/7 : Prepare Release Upload")
        val preparedUpload = apiClient.prepareReleaseUpload(ownerName, appName)
            .execute()
            .bodyOrThrow()

        logger("Step 2/7 : Setting Metadata")
        val uploadApi = uploadApiFactory(preparedUpload.uploadDomain)
        val metadata = uploadApi.setMetadata(
            preparedUpload.packageAssetId,
            file.name,
            file.length(),
            preparedUpload.token,
            CONTENT_TYPE_APK
        ).execute().bodyOrThrow()

        logger("Step 3/7 : Upload Release Chunks")
        metadata.chunkList.forEachIndexed { i, chunkId ->
            val range = (i * metadata.chunkSize)..((i + 1) * metadata.chunkSize)
            val chunk = ProgressRequestBody(file, range, "application/octet-stream")
            uploadApi.uploadChunk(
                preparedUpload.packageAssetId,
                chunkId,
                preparedUpload.token,
                chunk
            ).execute().bodyOrThrow()
        }

        logger("Step 4/7 : Finish Upload")
        val finishResponse = uploadApi.finishUpload(
            metadata.id,
            preparedUpload.token
        ).execute().bodyOrThrow()

        logger("Step 5/7 : Commit Release")
        val commitRequest = CommitReleaseUploadRequest("uploadFinished")
        apiClient.commitReleaseUpload(ownerName, appName, preparedUpload.id, commitRequest).execute().bodyOrThrow()

        logger("Step 6/7 : Fetching Release Id")
        var requestCount = 0
        var uploadResult: GetUploadResponse?
        do {
            uploadResult = apiClient.getUpload(ownerName, appName, preparedUpload.id).execute().bodyOrThrow()
            Thread.sleep(BACKOFF_DELAY)
            if (++requestCount >= MAX_RETRIES) {
                throw AppCenterUploaderException("Fetching release id: Tried $requestCount times.")
            }
        } while (uploadResult?.uploadStatus != "readyToBePublished")

        println("AppCenter release url is ${uploadResult.releaseUrl}")

        logger("Step 7/7 : Distribute Release")
        val request = DistributeRequest(
            destinations = destinationNames.map { DistributeRequest.Destination(it) }.toList(),
            releaseNotes = changeLog,
            notifyTesters = notifyTesters
        )

        apiClient.distribute(ownerName, appName, uploadResult.releaseId!!, request).execute().successOrThrow()
    }

    fun uploadSymbols(mappingFile: File, symbolType:String, versionName: String, versionCode: String, logger: (String) -> Unit) {
        logger("Step 1/3 : Prepare Symbol")
        val prepareRequest = PrepareSymbolUploadRequest(
            symbolType = symbolType,
            fileName = mappingFile.name,
            version = versionName,
            build = versionCode
        )
        val prepareResponse = apiClient.prepareSymbolUpload(ownerName, appName, prepareRequest).execute()
        if (!prepareResponse.isSuccessful) {
            throw AppCenterUploaderException(
                "Can't prepare symbol upload, code=${prepareResponse.code()}, " +
                        "reason=${prepareResponse.errorBody()?.string()}"
            )
        }

        val preparedUpload = prepareResponse.body()!!

        logger("Step 2/3 : Upload Symbol")
        val uploadResponse = doUploadSymbol(preparedUpload.uploadUrl, mappingFile).execute()
        if (!uploadResponse.isSuccessful) {
            throw AppCenterUploaderException(
                "Can't upload mapping, code=${uploadResponse.code}, " +
                        "reason=${uploadResponse.body?.string()}"
            )
        }

        logger("Step 3/3 : Commit Symbol")
        val commitRequest = CommitSymbolUploadRequest("committed")
        val commitResponse = apiClient.commitSymbolUpload(ownerName, appName, preparedUpload.symbolUploadId, commitRequest).execute()
        if (!commitResponse.isSuccessful) {
            throw AppCenterUploaderException(
                "Can't commit symbol, code=${commitResponse.code()}, " +
                        "reason=${commitResponse.errorBody()?.string()}"
            )
        }
    }

    private fun doUploadSymbol(uploadUrl: String, file: File): Call {
        val request = Request.Builder()
            .url(uploadUrl)
            .addHeader("x-ms-blob-type", "BlockBlob")
            .put(RequestBody.create("text/plain; charset=UTF-8".toMediaTypeOrNull(), file))
            .build()

        return okHttpClient.newCall(request)
    }
}

private fun <T> Response<T>.bodyOrThrow() = successOrThrow()!!

private fun <T> Response<T>.successOrThrow() =
    if (isSuccessful) {
        body()
    } else {
        throw AppCenterUploaderException("Can't prepare release upload, code=${code()}, reason=${errorBody()?.string()}")
    }

class AppCenterUploaderException(message: String) : Exception(message)
