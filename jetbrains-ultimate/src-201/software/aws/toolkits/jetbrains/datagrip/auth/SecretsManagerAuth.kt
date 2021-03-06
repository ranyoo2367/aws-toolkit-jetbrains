// Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.datagrip.auth

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.intellij.credentialStore.Credentials
import com.intellij.database.Dbms
import com.intellij.database.access.DatabaseCredentials
import com.intellij.database.dataSource.DatabaseAuthProvider
import com.intellij.database.dataSource.DatabaseAuthProvider.AuthWidget
import com.intellij.database.dataSource.DatabaseConnectionInterceptor.ProtoConnection
import com.intellij.database.dataSource.DatabaseCredentialsAuthProvider
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.future
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import software.aws.toolkits.core.utils.getLogger
import software.aws.toolkits.core.utils.info
import software.aws.toolkits.jetbrains.core.awsClient
import software.aws.toolkits.jetbrains.core.credentials.ConnectionSettings
import software.aws.toolkits.jetbrains.datagrip.getAwsConnectionSettings
import software.aws.toolkits.jetbrains.datagrip.getDatabaseEngine
import software.aws.toolkits.jetbrains.utils.ApplicationThreadPoolScope
import software.aws.toolkits.resources.message
import software.aws.toolkits.telemetry.DatabaseCredentials.SecretsManager
import software.aws.toolkits.telemetry.RdsTelemetry
import software.aws.toolkits.telemetry.RedshiftTelemetry
import software.aws.toolkits.telemetry.Result
import java.util.concurrent.CompletionStage

data class SecretsManagerConfiguration(
    val connectionSettings: ConnectionSettings,
    val secretId: String
)

class SecretsManagerAuth : DatabaseAuthProvider, CoroutineScope by ApplicationThreadPoolScope("RedshiftSecretsManagerAuth") {
    private val objectMapper = jacksonObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    override fun getId(): String = providerId
    override fun isApplicable(dataSource: LocalDataSource): Boolean {
        val dbms = dataSource.dbms
        return dbms == Dbms.MYSQL || dbms == Dbms.POSTGRES || dbms == Dbms.REDSHIFT
    }

    override fun getDisplayName(): String = message("datagrip.auth.secrets_manager")

    override fun createWidget(creds: DatabaseCredentials, source: LocalDataSource): AuthWidget? =
        SecretsManagerAuthWidget()

    override fun intercept(
        connection: ProtoConnection,
        silent: Boolean
    ): CompletionStage<ProtoConnection>? {
        LOG.info { "Intercepting db connection [$connection]" }
        return future {
            var result = Result.Succeeded
            val project = connection.runConfiguration.project
            try {
                val connectionSettings = getConfiguration(connection)
                val credentials = getCredentials(connection.runConfiguration.project, connectionSettings)
                DatabaseCredentialsAuthProvider.applyCredentials(connection, credentials, true)
            } catch (e: Throwable) {
                result = Result.Failed
                throw e
            } finally {
                val engine = connection.getDatabaseEngine()
                if (engine == "redshift") {
                    RedshiftTelemetry.getCredentials(project, result, SecretsManager)
                } else {
                    RdsTelemetry.getCredentials(project, result, SecretsManager, engine)
                }
            }
        }
    }

    private fun getConfiguration(connection: ProtoConnection): SecretsManagerConfiguration {
        val connectionSettings = connection.getAwsConnectionSettings()
        val secretId = connection.connectionPoint.additionalJdbcProperties[SECRET_ID_PROPERTY]
            ?: throw IllegalArgumentException(message("datagrip.secretsmanager.validation.no_secret"))
        return SecretsManagerConfiguration(
            connectionSettings,
            secretId
        )
    }

    private fun getCredentials(project: Project, configuration: SecretsManagerConfiguration): Credentials {
        val client = project.awsClient<SecretsManagerClient>(configuration.connectionSettings.credentials, configuration.connectionSettings.region)
        val secret = client.getSecretValue { it.secretId(configuration.secretId) }
        val dbSecret = objectMapper.readValue<SecretsManagerDbSecret>(secret.secretString())
        dbSecret.username ?: throw IllegalArgumentException(message("datagrip.secretsmanager.validation.no_username", secret.name()))
        dbSecret.password ?: throw IllegalArgumentException(message("datagrip.secretsmanager.validation.no_password", secret.name()))
        return Credentials(dbSecret.username, dbSecret.password)
    }

    companion object {
        const val providerId = "aws.secretsmanager"
        private val LOG = getLogger<SecretsManagerAuth>()
    }
}
