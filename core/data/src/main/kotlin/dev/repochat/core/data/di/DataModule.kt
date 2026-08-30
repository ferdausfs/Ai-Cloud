package dev.repochat.core.data.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.repochat.core.data.local.ActiveRepoDao
import dev.repochat.core.data.local.AppDatabase
import dev.repochat.core.data.local.ChatMessageDao
import dev.repochat.core.data.local.EncryptedSettingsStore
import dev.repochat.core.data.local.RepoSessionDao
import dev.repochat.core.data.remote.GithubApi
import dev.repochat.core.data.remote.GithubAuthInterceptor
import dev.repochat.core.data.remote.OllamaApi
import dev.repochat.core.data.remote.OllamaAuthInterceptor
import dev.repochat.core.data.repository.ActiveRepoRepositoryImpl
import dev.repochat.core.data.repository.ChatRepositoryImpl
import dev.repochat.core.data.repository.GithubRepositoryImpl
import dev.repochat.core.data.repository.OllamaRepositoryImpl
import dev.repochat.core.data.repository.SettingsRepositoryImpl
import dev.repochat.core.domain.ActiveRepoRepository
import dev.repochat.core.domain.AiEditOrchestrator
import dev.repochat.core.domain.AiTurnRunner
import dev.repochat.core.domain.ChatRepository
import dev.repochat.core.domain.GithubService
import dev.repochat.core.domain.OllamaService
import dev.repochat.core.domain.SettingsRepository
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    @Singleton
    abstract fun bindGithubService(impl: GithubRepositoryImpl): GithubService

    @Binds
    @Singleton
    abstract fun bindOllamaService(impl: OllamaRepositoryImpl): OllamaService

    @Binds
    @Singleton
    abstract fun bindChatRepository(impl: ChatRepositoryImpl): ChatRepository

    @Binds
    @Singleton
    abstract fun bindActiveRepoRepository(impl: ActiveRepoRepositoryImpl): ActiveRepoRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindAiTurnRunner(impl: AiEditOrchestrator): AiTurnRunner

    companion object {

        @Provides
        @Singleton
        fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
            androidx.room.Room.databaseBuilder(context, AppDatabase::class.java, "repochat.db")
                .addMigrations(AppDatabase.MIGRATION_1_2)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()

        @Provides
        @Singleton
        fun provideEncryptedSettingsStore(@ApplicationContext context: Context): EncryptedSettingsStore =
            EncryptedSettingsStore.create(context)

        @Provides
        @Singleton
        fun provideRepoSessionDao(db: AppDatabase): RepoSessionDao = db.repoSessionDao()

        @Provides
        @Singleton
        fun provideChatMessageDao(db: AppDatabase): ChatMessageDao = db.chatMessageDao()

        @Provides
        @Singleton
        fun provideActiveRepoDao(db: AppDatabase): ActiveRepoDao = db.activeRepoDao()

        @Provides
        @Singleton
        fun provideJson(): Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            // Omit null optional fields (e.g. OllamaMessageDto.images) so
            // text-only chat payloads stay clean for non-vision models.
            explicitNulls = false
        }

        @Provides
        @Singleton
        fun provideGithubApi(json: Json, settings: SettingsRepository): GithubApi {
            val client = OkHttpClient.Builder()
                .addInterceptor(GithubAuthInterceptor { settings.cached().githubPat })
                .build()
            return Retrofit.Builder()
                .baseUrl("https://api.github.com/")
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(GithubApi::class.java)
        }

        @Provides
        @Singleton
        fun provideOllamaApi(json: Json, settings: SettingsRepository): OllamaApi {
            // LLM calls (especially large cloud models with a full repo tree in
            // context) routinely exceed OkHttp's 10s defaults — give generous
            // timeouts so a slow generation surfaces as a real response/error
            // instead of a generic SocketTimeoutException.
            val client = OkHttpClient.Builder()
                .addInterceptor(OllamaAuthInterceptor { settings.cached().ollamaKey })
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            return Retrofit.Builder()
                .baseUrl("https://ollama.com/")
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(OllamaApi::class.java)
        }
    }
}
