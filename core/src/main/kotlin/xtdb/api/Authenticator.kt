@file:UseSerializers(UrlSerializer::class)

package xtdb.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import xtdb.UrlSerializer
import xtdb.api.Authenticator.Method.TRUST
import xtdb.api.Authenticator.MethodRule
import xtdb.util.requiringResolve
import java.net.URL

val DEFAULT_RULES = listOf(MethodRule(TRUST))

interface Authenticator : AutoCloseable {
    fun methodFor(user: String?, remoteAddress: String?): Method

    fun verifyPassword(db: Xtdb, user: String, password: String): String =
        throw UnsupportedOperationException("password auth not supported")

    interface DeviceAuthResponse {
        val url: URL
        fun await(): String
    }

    fun startDeviceAuth(user: String): DeviceAuthResponse =
        throw UnsupportedOperationException("device auth not supported")

    override fun close() = Unit

    @Serializable
    enum class Method {
        TRUST,
        PASSWORD,
        DEVICE_AUTH,
    }

    @Serializable
    data class MethodRule(
        val method: Method,
        val user: String? = null,
        val remoteAddress: String? = null
    )

    interface Factory {
        var rules: List<MethodRule>

        fun rules(rules: List<MethodRule>) = apply { this.rules = rules }

        fun open(): Authenticator

        @Serializable
        @SerialName("!UserTable")
        data class UserTable(override var rules: List<MethodRule> = DEFAULT_RULES) : Factory {
            override fun open() =
                requiringResolve("xtdb.authn/->user-table-authn")(this) as Authenticator
        }

        @Serializable
        @SerialName("!OpenIdConnect")
        data class OpenIdConnect(
            val realmUrl: URL,
            @Serializable(with = StringWithEnvVarSerde::class) val clientId: String,
            @Serializable(with = StringWithEnvVarSerde::class) val clientSecret: String,
            override var rules: List<MethodRule> = DEFAULT_RULES
        ) : Factory {
            override fun open() =
                requiringResolve("xtdb.authn/->oidc-authn")(this) as Authenticator
        }
    }
}
