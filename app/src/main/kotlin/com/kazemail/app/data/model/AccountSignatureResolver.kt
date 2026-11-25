package com.kazemail.app.data.model

import com.kazemail.app.data.db.AccountEntity

data class ResolvedSignatureState(
    val enabled: Boolean,
    val text: String,
    val inheritedFromLegacyGlobal: Boolean,
)

/**
 * Resolves the effective email signature for a specific account/address.
 *
 * Accounts that already existed before per-account signature configuration (`signatureConfigured == false`)
 * transparently inherit the legacy global signature preference until configured per account.
 */
object AccountSignatureResolver {
    fun resolveState(
        account: AccountEntity?,
        legacyEnabled: Boolean,
        legacyText: String,
    ): ResolvedSignatureState {
        if (account == null) {
            return ResolvedSignatureState(
                enabled = legacyEnabled,
                text = legacyText,
                inheritedFromLegacyGlobal = true,
            )
        }
        return if (account.signatureConfigured) {
            ResolvedSignatureState(
                enabled = account.signatureEnabled,
                text = account.signatureText,
                inheritedFromLegacyGlobal = false,
            )
        } else {
            ResolvedSignatureState(
                enabled = legacyEnabled,
                text = legacyText,
                inheritedFromLegacyGlobal = true,
            )
        }
    }

    fun resolveActiveSignatureText(
        account: AccountEntity?,
        legacyEnabled: Boolean,
        legacyText: String,
    ): String? {
        val state = resolveState(
            account = account,
            legacyEnabled = legacyEnabled,
            legacyText = legacyText,
        )
        return if (state.enabled && state.text.isNotBlank()) state.text else null
    }
}
