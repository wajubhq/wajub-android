package com.wajub.mobile.mobilemoney

import com.wajub.mobile.mapper.int
import com.wajub.mobile.mapper.obj
import com.wajub.mobile.mapper.objects
import com.wajub.mobile.mapper.str
import com.wajub.mobile.mapper.strings
import org.json.JSONObject

/** One country of the Mobile Money tables. */
data class MobileMoneyCountry(
    /** ISO-2, upper case. */
    val code: String,
    val name: String,
    /** `+237`. */
    val dialCode: String,
    /** Digits a valid number has, or null to accept 7 to 15 digits. */
    val nationalNumberLength: Int?,
    /** The leading 0 is part of the number (Côte d'Ivoire, Benin…): the international format keeps it. */
    val leadingZeroSignificant: Boolean,
    val placeholder: String,
    val placeholderFormatted: String,
    val operators: List<String>,
    val prefixRules: List<PrefixRule>,
    /** What every valid number starts with (`0` in Côte d'Ivoire, `01` in Benin), or null. */
    val numberPrefix: String? = null,
    /** The mobile ranges a number typed without its leading 0 is read on. */
    val mobilePrefixes: List<String> = emptyList(),
    /** How numbers of an earlier, shorter plan read today, or null. */
    val earlier: EarlierPlan? = null,
)

data class PrefixRule(val startsWith: List<String>, val operator: String)

/**
 * Numbers of an earlier plan: [length] digits starting with one of [map]'s
 * keys read with that key replaced by its value (Benin: `96…` → `0196…`).
 */
data class EarlierPlan(val length: Int, val map: Map<String, String>)

/** A sandbox test number: the operator it tests, and the number as it is sent. */
data class SandboxTestNumber(val operator: String, val e164: String)

/** A badge drawn when an operator has no logo, or its logo does not load. */
data class BrandBadge(
    val label: String,
    /** Text drawn in the badge. */
    val mark: String,
    /** `#RRGGBB` (or `#RGB`). */
    val color: String,
    val background: String,
)

/** The Mobile Money tables: countries, dial codes, number lengths and operators. */
class MobileMoneyData internal constructor(
    val schemaVersion: Int,
    val countries: Map<String, MobileMoneyCountry>,
    private val brands: Map<String, BrandBadge?>,
    /** Wallets held whatever the operator: any number may pay with them. */
    val operatorIndependentWallets: Set<String>,
    private val fallbackColor: String,
    private val fallbackBackground: String,
    private val looseMin: Int,
    private val looseMax: Int,
    // Sandbox test numbers: per country, per operator, the prefixes (digits only).
    private val sandboxPrefixes: Map<String, Map<String, List<String>>> = emptyMap(),
    private val outcomeSuffixes: List<String> = emptyList(),
) {
    fun country(code: String?): MobileMoneyCountry? = code?.let { countries[it.uppercase()] }

    /** The brand label for [operator] (`MTN MoMo`), when the tables have one. */
    fun brandLabel(operator: String): String? = brands[operator]?.label

    /** The badge for [operator]; [fallbackName] names it when the tables have no brand. */
    fun badge(operator: String, fallbackName: String): BrandBadge {
        val brand = brands[operator]
        if (brand != null) return brand
        return BrandBadge(fallbackName, markOf(fallbackName), fallbackColor, fallbackBackground)
    }

    internal fun looseValid(digits: Int): Boolean = digits in looseMin..looseMax

    // The operator of the longest sandbox prefix [reading] starts with, when an outcome follows it.
    internal fun sandboxOperator(country: String, reading: String): String? {
        val byOperator = sandboxPrefixes[country.uppercase()] ?: return null
        var best: Pair<String, Int>? = null
        for ((operator, prefixes) in byOperator) {
            for (prefix in prefixes) {
                if (!reading.startsWith(prefix)) continue
                val rest = reading.substring(prefix.length)
                val outcome = outcomeSuffixes.any { suffix ->
                    rest.endsWith(suffix) && rest.substring(0, rest.length - suffix.length).all { it == '0' }
                }
                if (outcome && (best == null || prefix.length > best.second)) best = operator to prefix.length
            }
        }
        return best?.first
    }

    companion object {
        /** The only version of the tables this SDK reads. */
        const val SUPPORTED_SCHEMA_VERSION: Int = 2

        fun parse(text: String): MobileMoneyData {
            val root = JSONObject(text)
            val version = root.int("schema_version")
            require(version == SUPPORTED_SCHEMA_VERSION) {
                "mobile-money.json schema_version $version is not supported (expected $SUPPORTED_SCHEMA_VERSION)"
            }
            val countries = linkedMapOf<String, MobileMoneyCountry>()
            root.obj("countries")?.let { all ->
                all.keys().forEach { code ->
                    val c = all.getJSONObject(code)
                    countries[code.uppercase()] = MobileMoneyCountry(
                        code = code.uppercase(),
                        name = c.str("name") ?: code,
                        dialCode = c.str("dial_code").orEmpty(),
                        nationalNumberLength = c.int("national_number_length"),
                        leadingZeroSignificant = c.str("leading_zero") == "significant",
                        placeholder = c.str("placeholder").orEmpty(),
                        placeholderFormatted = c.str("placeholder_formatted").orEmpty(),
                        operators = c.strings("operators"),
                        prefixRules = c.objects("prefix_rules").mapNotNull { rule ->
                            rule.str("operator")?.let { PrefixRule(rule.strings("starts_with"), it) }
                        },
                        numberPrefix = c.str("number_prefix")?.takeIf { it.isNotEmpty() },
                        mobilePrefixes = c.strings("mobile_prefixes"),
                        earlier = c.obj("earlier")?.let { e ->
                            val length = e.int("length")
                            val map = linkedMapOf<String, String>()
                            e.obj("map")?.let { m -> m.keys().forEach { key -> m.str(key)?.let { map[key] = it } } }
                            if (length == null || map.isEmpty()) null else EarlierPlan(length, map)
                        },
                    )
                }
            }
            val fallback = root.obj("fallback_brand")
            val fallbackColor = fallback?.str("color") ?: "#ffffff"
            val fallbackBackground = fallback?.str("background") ?: "#525252"
            val brands = linkedMapOf<String, BrandBadge?>()
            root.obj("operators")?.let { all ->
                all.keys().forEach { id ->
                    val op = all.getJSONObject(id)
                    brands[id] = op.obj("brand")?.let { b ->
                        val label = b.str("label") ?: op.str("name") ?: id
                        BrandBadge(
                            label = label,
                            mark = b.str("mark") ?: markOf(label),
                            color = b.str("color") ?: fallbackColor,
                            background = b.str("background") ?: fallbackBackground,
                        )
                    }
                }
            }
            val loose = root.obj("loose_length")
            val sandbox = root.obj("sandbox_test_numbers")
            val sandboxPrefixes = linkedMapOf<String, Map<String, List<String>>>()
            sandbox?.obj("prefixes")?.let { all ->
                all.keys().forEach { country ->
                    val byOperator = linkedMapOf<String, List<String>>()
                    all.obj(country)?.let { ops -> ops.keys().forEach { op -> byOperator[op] = ops.strings(op).map { it.removePrefix("+") } } }
                    sandboxPrefixes[country.uppercase()] = byOperator
                }
            }
            return MobileMoneyData(
                schemaVersion = version,
                countries = countries,
                brands = brands,
                operatorIndependentWallets = root.strings("operator_independent_wallets").toSet(),
                fallbackColor = fallbackColor,
                fallbackBackground = fallbackBackground,
                looseMin = loose?.int("min") ?: 7,
                looseMax = loose?.int("max") ?: 15,
                sandboxPrefixes = sandboxPrefixes,
                outcomeSuffixes = sandbox?.obj("outcome_suffixes")?.keys()?.asSequence()?.toList().orEmpty(),
            )
        }

        // algorithms.brand_fallback: the first 8 alphanumerics, upper-cased.
        private fun markOf(label: String): String = label.filter { it.isLetterOrDigit() }.take(8).uppercase()
    }
}

/**
 * The Mobile Money number rules: clean-up, validity, operator detection,
 * international format and the as-you-type format.
 */
object MobileMoney {
    private const val RESOURCE = "/com/wajub/mobile/mobile-money.json"

    @Volatile
    private var loaded: MobileMoneyData? = null

    /** The tables, read once from the SDK's copy of `mobile-money.json`. */
    fun data(): MobileMoneyData =
        loaded ?: synchronized(this) {
            loaded ?: run {
                val stream = MobileMoney::class.java.getResourceAsStream(RESOURCE)
                    ?: error("$RESOURCE is missing from the SDK")
                MobileMoneyData.parse(stream.bufferedReader(Charsets.UTF_8).use { it.readText() }).also { loaded = it }
            }
        }

    /** Digits only, as 0-9 whatever script they were typed in; everything else is dropped (a `+` too). */
    fun sanitize(input: String): String = buildString {
        for (c in input) {
            val digit = Character.digit(c, 10)
            if (digit >= 0) append('0' + digit)
        }
    }

    /** The digits as typed, without the dial code. */
    fun typedNationalDigits(input: String, country: MobileMoneyCountry): String {
        val digits = sanitize(input)
        val dial = sanitize(country.dialCode)
        return if (dial.isNotEmpty() && digits.startsWith(dial)) digits.removePrefix(dial) else digits
    }

    /**
     * The number as the country reads it today, without its dial code: an
     * earlier plan's number in its new form (Benin `96 12 34 56` → `0196123456`),
     * the leading 0 kept where it is part of the number (and given back to a
     * mobile number typed without it), dropped elsewhere.
     */
    fun nationalDigits(input: String, country: MobileMoneyCountry): String {
        var digits = typedNationalDigits(input, country)
        val earlier = country.earlier
        if (earlier != null && digits.length == earlier.length) {
            earlier.map.keys.filter { digits.startsWith(it) }.maxByOrNull { it.length }?.let { key ->
                digits = earlier.map.getValue(key) + digits.substring(key.length)
            }
        }
        if (!country.leadingZeroSignificant) return digits.trimStart('0')
        if (!digits.startsWith("0")) {
            val withZero = "0$digits"
            if (withZero.length == country.nationalNumberLength && country.mobilePrefixes.any { withZero.startsWith(it) }) return withZero
        }
        return digits
    }

    /** Whether the number is complete for [country]; in a [sandbox] session, a sandbox test number is. */
    fun isValid(input: String, country: MobileMoneyCountry, sandbox: Boolean = false): Boolean {
        if (sandbox && sandboxTestNumber(input, country) != null) return true
        val length = country.nationalNumberLength ?: return data().looseValid(sanitize(input).length)
        val digits = nationalDigits(input, country)
        return digits.length == length && country.numberPrefix?.let { digits.startsWith(it) } != false
    }

    /**
     * The operator the number belongs to, or null; in a [sandbox] session, the
     * operator a sandbox test number tests.
     */
    fun detectOperator(input: String, country: MobileMoneyCountry, sandbox: Boolean = false): String? {
        if (sandbox) sandboxTestNumber(input, country)?.let { return it.operator }
        var digits = nationalDigits(input, country)
        if (digits.isEmpty()) return null
        val prefix = country.numberPrefix
        // A number still being typed without its 0 names its operator as it is typed.
        if (prefix != null && !digits.startsWith("0")) {
            val key = country.earlier?.map?.keys?.filter { digits.startsWith(it) }?.maxByOrNull { it.length }
            digits = if (key != null) country.earlier.map.getValue(key) + digits.substring(key.length) else "0$digits"
        }
        val independent = data().operatorIndependentWallets
        return country.prefixRules.firstOrNull { rule -> rule.startsWith.any { digits.startsWith(it) } }
            ?.operator
            ?.takeIf { it !in independent }
    }

    /** The number in international format (`+237677123456`, `+2250707000000`); a [sandbox] test number as it was read. */
    fun e164(input: String, country: MobileMoneyCountry, sandbox: Boolean = false): String {
        if (sandbox) sandboxTestNumber(input, country)?.let { return it.e164 }
        return country.dialCode + nationalDigits(input, country)
    }

    /** A sandbox test number of [country], or null (it means something in a sandbox session only). */
    fun sandboxTestNumber(input: String, country: MobileMoneyCountry): SandboxTestNumber? {
        val dial = sanitize(country.dialCode)
        for (reading in listOf(dial + nationalDigits(input, country), dial + typedNationalDigits(input, country))) {
            data().sandboxOperator(country.code, reading)?.let { return SandboxTestNumber(it, "+$reading") }
        }
        return null
    }

    /** Digits grouped by two: `671234567` → `67 12 34 56 7` (at most 15 digits). */
    fun formatAsYouType(input: String): String = sanitize(input).take(15).chunked(2).joinToString(" ")
}
