package com.example.tangeml2.domain

import com.example.tangeml2.domain.model.AssetType
import com.example.tangeml2.domain.model.Network

/**
 * Every user intent the recovery screen can emit.
 *
 * Implemented by [RecoveryViewModel]; the Compose UI only ever talks to this
 * interface, which keeps screens free of business logic and testable in isolation.
 */
interface RecoveryActions {

    // ── Network ────────────────────────────────────────────────────────────
    fun selectNetwork(network: Network)
    fun openAddNetwork()
    fun dismissAddNetwork()
    fun addCustomNetwork(name: String, rpcUrl: String, explorerUrl: String)
    fun removeCustomNetwork(network: Network)

    // ── Card ───────────────────────────────────────────────────────────────
    fun scanCard()
    fun refreshBalance()

    // ── Asset / form ───────────────────────────────────────────────────────
    fun setAssetType(type: AssetType)
    fun setTokenAddress(value: String)
    fun loadToken()
    fun setRecipient(value: String)
    fun setAmount(value: String)
    fun setCallValue(value: String)
    fun setCalldata(value: String)
    fun fillMaxAmount()
    fun fillOwnAddress()

    // ── QR ─────────────────────────────────────────────────────────────────
    /** Raw text from the QR scanner; parsed into a review sheet or an error. */
    fun onQrScanned(raw: String)
    fun dismissQrReview()
    /** Accept the scanned call: fills the form and opens the confirm sheet. */
    fun acceptQrReview()
    /** Accept the scanned call but only prefill the form, no confirmation. */
    fun prefillFromQrReview()

    // ── Send ───────────────────────────────────────────────────────────────
    /** Validates the form, prices the transaction and opens the confirm sheet. */
    fun requestSend()
    fun dismissConfirm()
    /** Confirmed: build, NFC-sign, broadcast, then poll for the receipt. */
    fun confirmSend()

    // ── Log ────────────────────────────────────────────────────────────────
    fun openLog()
    fun closeLog()
    fun refreshLog()
    fun clearLog()
    /** Writes the log to a shareable file and returns it (null when it failed). */
    suspend fun exportLog(): java.io.File?

    // ── Status ─────────────────────────────────────────────────────────────
    fun dismissStatus()
}
