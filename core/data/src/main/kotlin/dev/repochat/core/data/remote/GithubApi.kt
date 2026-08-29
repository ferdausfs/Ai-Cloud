package dev.repochat.core.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl

/**
 * GitHub REST API v3 surface used by the app. Branch refs and tree refs use
 * encoded=true path parameters so slash-containing refs (ai-chat/abc12345)
 * are sent as multi-segment paths, which the API accepts. File paths go
 * through [HttpUrl] so every segment is canonicalized independently.
 */
interface GithubApi {

    @retrofit2.http.GET("user")
    suspend fun currentUser(): GithubUserDto

    @retrofit2.http.GET("user/repos")
    suspend fun listRepos(
        @retrofit2.http.Query("per_page") perPage: Int,
        @retrofit2.http.Query("page") page: Int,
        @retrofit2.http.Query("sort") sort: String = "updated",
        @retrofit2.http.Query("direction") direction: String = "desc",
    ): List<GithubRepoDto>

    @retrofit2.http.GET("repos/{owner}/{repo}/git/refs/heads/{branch}")
    suspend fun branch(
        @retrofit2.http.Path("owner") owner: String,
        @retrofit2.http.Path("repo") repo: String,
        @retrofit2.http.Path("branch", encoded = true) branch: String,
    ): GithubRefResponseDto

    @retrofit2.http.POST("repos/{owner}/{repo}/git/refs")
    suspend fun createRef(
        @retrofit2.http.Path("owner") owner: String,
        @retrofit2.http.Path("repo") repo: String,
        @retrofit2.http.Body body: GithubCreateRefRequestDto,
    ): GithubRefResponseDto

    @retrofit2.http.GET("repos/{owner}/{repo}/git/trees/{tree_sha}")
    suspend fun tree(
        @retrofit2.http.Path("owner") owner: String,
        @retrofit2.http.Path("repo") repo: String,
        @retrofit2.http.Path("tree_sha", encoded = true) ref: String,
        @retrofit2.http.Query("recursive") recursive: Int = 1,
    ): GithubTreeResponseDto

    @retrofit2.http.GET
    suspend fun file(@retrofit2.http.Url url: HttpUrl): GithubFileContentDto

    @retrofit2.http.PUT
    suspend fun putFile(
        @retrofit2.http.Url url: HttpUrl,
        @retrofit2.http.Body body: GithubPutFileRequestDto,
    ): GithubPutFileResponseDto

    @retrofit2.http.POST("repos/{owner}/{repo}/pulls")
    suspend fun createPullRequest(
        @retrofit2.http.Path("owner") owner: String,
        @retrofit2.http.Path("repo") repo: String,
        @retrofit2.http.Body body: GithubCreatePullRequestDto,
    ): GithubPullRequestDto
}

/* ---------------------------- DTOs ---------------------------- */

@Serializable
data class GithubUserDto(val login: String)

@Serializable
data class GithubRepoDto(
    val id: Long,
    val name: String,
    @SerialName("full_name") val fullName: String,
    @SerialName("private") val isPrivate: Boolean = false,
    val description: String? = null,
    val language: String? = null,
    @SerialName("default_branch") val defaultBranch: String = "main",
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
)

@Serializable
data class GithubRefResponseDto(
    val ref: String,
    @SerialName("object") val obj: GithubRefObjectDto,
)

@Serializable
data class GithubRefObjectDto(val sha: String)

@Serializable
data class GithubCreateRefRequestDto(
    val ref: String,
    val sha: String,
)

@Serializable
data class GithubTreeResponseDto(
    val truncated: Boolean = false,
    val tree: List<GithubTreeEntryDto> = emptyList(),
)

@Serializable
data class GithubTreeEntryDto(
    val path: String,
    val type: String,
    val size: Long? = null,
)

@Serializable
data class GithubFileContentDto(
    val path: String? = null,
    val sha: String? = null,
    val content: String? = null,
    val encoding: String? = null,
    val type: String? = null,
    val size: Long? = null,
)

@Serializable
data class GithubPutFileRequestDto(
    val message: String,
    val content: String,
    val sha: String? = null,
    val branch: String? = null,
)

@Serializable
data class GithubPutFileResponseDto(val content: GithubFileContentDto? = null)

@Serializable
data class GithubCreatePullRequestDto(
    val title: String,
    val head: String,
    val base: String,
    val body: String? = null,
)

@Serializable
data class GithubPullRequestDto(
    val number: Long? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    val title: String = "",
)

@Serializable
data class GithubErrorDto(val message: String? = null)
