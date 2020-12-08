package com.betomorrow.gradle.appcenter.infra

import org.gradle.api.Project

class AppCenterUploaderFactory(
    private val project: Project
) {

    fun create(apiToken: String, ownerName: String, appName: String): AppCenterUploader {
        val debug = true
        val apiFactory = AppCenterAPIFactory(project)
        val api = apiFactory.create(apiToken, debug)
        val httpClient = OkHttpBuilder(project).logger(debug).build()
        return AppCenterUploader({uploadDomain:String -> apiFactory.createUploadApi(uploadDomain, apiToken, debug)}, api, httpClient, ownerName, appName)
    }
}
