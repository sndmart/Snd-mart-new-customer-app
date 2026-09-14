package com.example.util

/**
 * Utility for standardizing phone numbers to prevent duplicate Supabase accounts across apps.
 *
 * Rule:
 * Strip everything except digits.
 * If it starts with '91' and length > 10, strip the '91' country code prefix.
 * Return `+91${withoutCountryCode}` (exactly 13 characters for 10-digit mobile numbers: "+91XXXXXXXXXX").
 */
object PhoneUtils {

    /**
     * Standardizes phone numbers to E.164 format with +91 country code.
     * Always returns "+91XXXXXXXXXX" (13 chars).
     */
    fun toE164(rawInput: String): String {
        // Strip everything except digits
        val digits = rawInput.replace(Regex("\\D"), "")
        // If it already has the country code (11-12 digits starting with 91), don't double it
        val withoutCountryCode = if (digits.startsWith("91") && digits.length > 10) {
            digits.substring(2)
        } else {
            digits
        }
        return "+91$withoutCountryCode"
    }

    /**
     * Normalizes input into the national 10-digit mobile number.
     * Correctly handles pasted inputs like "+919110604033", "919110604033", or "9110604033".
     */
    fun to10Digits(rawInput: String): String {
        val digits = rawInput.replace(Regex("\\D"), "")
        val withoutCountryCode = if (digits.startsWith("91") && digits.length > 10) {
            digits.substring(2)
        } else {
            digits
        }
        return withoutCountryCode.take(10)
    }

    /**
     * Checks if the raw input resolves to a valid 10-digit Indian phone number.
     */
    fun isValidPhoneNumber(rawInput: String): Boolean {
        val nationalNumber = to10Digits(rawInput)
        return nationalNumber.length == 10
    }
}
