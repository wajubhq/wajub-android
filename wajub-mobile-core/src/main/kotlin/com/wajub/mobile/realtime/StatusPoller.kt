package com.wajub.mobile.realtime

import com.wajub.mobile.client.PayClient
import com.wajub.mobile.mapper.PaymentMapper
import com.wajub.mobile.model.PaymentResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

private val terminalStatuses = setOf("success", "successful", "succeeded", "paid", "complete", "failed", "cancelled", "expired")

class StatusPoller(
    private val client: PayClient,
    private val token: String,
    private val intervalMs: Long = 5_000L,
) {
    fun poll(): Flow<PaymentResult> = flow {
        var lastStatus: String? = null
        var failures = 0
        while (currentCoroutineContext().isActive) {
            try {
                val session = client.getSession(token)
                val status = session.transaction.status
                failures = 0
                if (status != lastStatus) {
                    lastStatus = status
                    val result = PaymentMapper.mapStatusToResult(status, session.transaction)
                    emit(result)
                    if (status.lowercase() in terminalStatuses) {
                        return@flow
                    }
                }
                delay(intervalMs)
            } catch (_: Exception) {
                failures++
                delay(minOf(intervalMs * failures, 60_000L))
            }
        }
    }
}
