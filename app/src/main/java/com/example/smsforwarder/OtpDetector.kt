package com.example.smsforwarder

import java.util.regex.Pattern

/**
 * OTP detection engine adapted from SecureOTP's extraction pipeline.
 * Thanks to Jatin (https://github.com/26JATIN) for the original
 * SecureOTP project that inspired this implementation.
 *
 * Uses a three-phase priority system with keyword gating to minimize
 * false positives while catching all common OTP formats.
 *
 * Phase 1 (high priority): Patterns that are structurally unambiguous
 *         -- run without requiring keyword context.
 * Phase 2 (medium priority): Contextual patterns that only fire when
 *         OTP-related keywords are present in the message.
 * Phase 3 (low priority): Broad numeric fallback, gated by keywords
 *         and length heuristics (prefers 4-6 digit codes).
 */
object OtpDetector {

    // ── High priority: structurally unambiguous ──

    private val highPriorityPatterns = listOf(
        Pattern.compile(
            "\\b([0-9]{3,12})\\s+(?:is|are)\\s+(?:otp|code|verification|pin|password|passcode|token)\\s+for\\b",
            Pattern.CASE_INSENSITIVE
        ),
        Pattern.compile(
            "(?:use|enter|type)\\s+(?:otp[\\s-])?([0-9]{3,12})\\b",
            Pattern.CASE_INSENSITIVE
        ),
        Pattern.compile("[\"'\\[\\(]([0-9]{3,12})[\"'\\]\\)]"),
        Pattern.compile(
            "to\\s+(?:accept|verify|confirm|login|signin|complete|proceed)\\s+\\w*\\s*([0-9]{3,12})\\b",
            Pattern.CASE_INSENSITIVE
        ),
    )

    // ── Medium priority: require OTP keyword context ──

    private val mediumPriorityPatterns = listOf(
        Pattern.compile(
            "(?:is|are|:)\\s*([0-9]{3,12})\\b",
            Pattern.CASE_INSENSITIVE
        ),
        Pattern.compile(
            "(?:verification|authentication|authorization|access|security|login|confirmation)\\s+(?:code|otp|pin|token|password)?\\s*(?:is|:|are)?\\s*([0-9]{3,12})\\b",
            Pattern.CASE_INSENSITIVE
        ),
        Pattern.compile("\\b([0-9]{2,4}[-\\s][0-9]{2,4}(?:[-\\s][0-9]{2,4})?)\\b"),
    )

    // ── Low priority: broad fallback, heavily gated ──

    private val lowPriorityPatterns = listOf(
        Pattern.compile("\\b([0-9]{4,10})\\b"),
    )

    // Pre-compiled word-boundary pattern for all OTP keywords
    // Prevents substring false positives ("decode" matching "code", etc.)
    private val keywordPattern: Pattern = Pattern.compile(
        "\\b(?:" + listOf(
            "otp", "verification", "code", "pin", "password", "passcode", "token",
            "authenticate", "verify", "security", "login", "signin", "sign-in", "sign in",
            "confirm", "confirmation", "activation", "activate", "authorization", "authorize",
            "2fa", "two-factor", "two factor", "mfa", "multi-factor", "multi factor",
            "temporary", "one-time", "onetime", "one time",
            "access", "authentication", "validation", "validate"
        ).joinToString("|") { Pattern.quote(it) } + ")\\b",
        Pattern.CASE_INSENSITIVE
    )

    // ── Values to reject ──

    private val ignorePatterns = listOf(
        Pattern.compile("^[0-9]{13,}$"),
        Pattern.compile("^[0-9]{1,3}$"),
        Pattern.compile("^(19|20)[0-9]{2}$"),
    )

    fun containsOtp(text: String): Boolean = extractOtp(text) != null

    fun extractOtp(text: String): String? {
        if (text.isBlank()) return null

        val hasContext = keywordPattern.matcher(text).find()

        findBestMatch(highPriorityPatterns, text)?.let { return it }

        if (!hasContext) return null

        findBestMatch(mediumPriorityPatterns, text)?.let { return it }

        val lowCandidates = collectMatches(lowPriorityPatterns, text)
            .filter { it.length in 4..8 }

        return lowCandidates
            .maxByOrNull { if (it.length in 4..6) 10 else 0 }
    }

    private fun findBestMatch(patterns: List<Pattern>, text: String): String? {
        for (pattern in patterns) {
            val matcher = pattern.matcher(text)
            while (matcher.find()) {
                val raw = (if (matcher.groupCount() > 0) matcher.group(1) else matcher.group(0))
                    ?: continue
                val clean = cleanMatch(raw)
                if (!shouldIgnore(clean)) return clean
            }
        }
        return null
    }

    private fun collectMatches(patterns: List<Pattern>, text: String): List<String> {
        val results = mutableListOf<String>()
        for (pattern in patterns) {
            val matcher = pattern.matcher(text)
            while (matcher.find()) {
                val raw = (if (matcher.groupCount() > 0) matcher.group(1) else matcher.group(0))
                    ?: continue
                val clean = cleanMatch(raw)
                if (!shouldIgnore(clean) && clean.length in 3..12) {
                    results.add(clean)
                }
            }
        }
        return results.distinct()
    }

    private fun cleanMatch(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.matches(Regex("[0-9\\s-]+"))) {
            trimmed.replace(Regex("[\\s-]"), "")
        } else {
            trimmed
        }
    }

    private fun shouldIgnore(value: String): Boolean {
        if (value.length !in 3..12) return true
        if (ignorePatterns.any { it.matcher(value).matches() }) return true
        if (value.length > 4 && value.matches(Regex("(.)\\1+"))) return true
        return false
    }
}
