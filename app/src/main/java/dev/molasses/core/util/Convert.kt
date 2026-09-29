package dev.molasses.core.util

/**
 * Unit conversion, for the handful of conversions a person actually does.
 *
 * ## The rule that admits a unit
 * **A unit is admitted only if its name denotes exactly one quantity
 * worldwide.**
 *
 * A rule with teeth rather than a taste filter, and it is here above the
 * table because it is what the next person needs before they add something.
 * It admits every unit below and it excludes one whole dimension.
 *
 * ## Volume is excluded, and this is the argument
 * A US gallon is 3.785 litres and an Imperial gallon is 4.546, a twenty per
 * cent difference, and `fl oz` differs between them too. A converter that
 * silently picks one is wrong for half the world with nothing on screen to
 * say which half, which is the fabricated-number failure this app removes
 * everywhere else rather than the kind of approximation it tolerates.
 *
 * The honest form would be explicit `usgal` and `impgal` tokens. That fails
 * the boundary rule's first clause, which asks whether opening the app that
 * does this costs more attention than the task: nobody opens a launcher to
 * type `usgal`. So volume is absent on purpose, and this paragraph is here so
 * the next person finds the argument before they find the gap.
 *
 * `st` is admitted on the rule as written rather than as an exception to it.
 * It is regional, and it is exactly fourteen pounds everywhere it is used.
 *
 * ## The rule binds a spelling too
 * A unit carries its other spellings, so `kilometers` and `km/h` reach the
 * same row, matched exactly and never fuzzily. The admission rule applies to
 * each of them: `kilo` is absent because a runner and a cook mean different
 * things by it, which is the same test `st` passed and volume failed.
 *
 * ## Why the table is affine and not a multiplier
 * Celsius to Fahrenheit is `c * 9/5 + 32`, which is not a ratio, so a table
 * of scale factors would need temperature to take its own path around it. An
 * affine entry, `base = value * factor + offset`, is one shape that holds
 * both: every ratio unit is an affine one with an offset of zero.
 *
 * One shape means one conversion function and no branch that only temperature
 * takes, which is the branch that would rot.
 *
 * Pure; no Android imports and no `java.*`. Unit-tested in `ConvertTest`.
 */
object Convert {

    /**
     * What a unit measures. Conversion happens inside one of these and
     * refuses across them, because `5 km` in pounds is not a question.
     */
    enum class Dimension { LENGTH, MASS, TEMPERATURE, SPEED }

    /**
     * One unit, as a mapping to its dimension's base.
     *
     * `base = value * factor + offset`, inverted for the other direction.
     * The base is metres, kilograms, degrees Celsius and kilometres per hour;
     * which one is arbitrary and only has to be consistent within a
     * dimension.
     */
    data class Unit(
        val token: String,
        val dimension: Dimension,
        val factor: Double,
        val offset: Double = 0.0,
        /**
         * Other spellings of the same unit, matched exactly.
         *
         * Exact and never fuzzy. A prefix or edit-distance match would turn
         * a typo into a silent wrong answer in a converter whose whole claim
         * is that it does not fabricate numbers, and `m` is one character
         * from `mi` in a table that holds both.
         *
         * They live on the unit rather than in a map beside it, so a
         * spelling cannot exist without a unit to belong to.
         */
        val aliases: List<String> = emptyList(),
    )

    /**
     * Every admitted unit.
     *
     * Eight pairs, sixteen directions. Read the rule above before adding a
     * ninth.
     */
    val UNITS: List<Unit> = listOf(
        // Length, base metres.
        Unit("m", Dimension.LENGTH, 1.0, aliases = listOf("meter", "meters", "metre", "metres")),
        Unit(
            "km", Dimension.LENGTH, 1000.0,
            aliases = listOf("kilometer", "kilometers", "kilometre", "kilometres"),
        ),
        Unit(
            "cm", Dimension.LENGTH, 0.01,
            aliases = listOf("centimeter", "centimeters", "centimetre", "centimetres"),
        ),
        Unit("mi", Dimension.LENGTH, 1609.344, aliases = listOf("mile", "miles")),
        Unit("ft", Dimension.LENGTH, 0.3048, aliases = listOf("foot", "feet")),
        Unit("in", Dimension.LENGTH, 0.0254, aliases = listOf("inch", "inches")),
        // Mass, base kilograms.
        //
        // "kilo" and "kilos" are deliberately absent. The admission rule
        // above applies to a spelling as much as to a unit, and a runner
        // saying "five kilos" means kilometres about as often as a cook
        // means kilograms. Every other alias here has one reading.
        Unit(
            "kg", Dimension.MASS, 1.0,
            aliases = listOf("kilogram", "kilograms", "kilogramme", "kilogrammes"),
        ),
        Unit("g", Dimension.MASS, 0.001, aliases = listOf("gram", "grams", "gramme", "grammes")),
        Unit("lb", Dimension.MASS, 0.45359237, aliases = listOf("lbs", "pound", "pounds")),
        Unit("oz", Dimension.MASS, 0.028349523125, aliases = listOf("ounce", "ounces")),
        Unit("st", Dimension.MASS, 6.35029318, aliases = listOf("stone", "stones")),
        // Temperature, base Celsius. The reason the table is affine.
        Unit("c", Dimension.TEMPERATURE, 1.0, aliases = listOf("celsius", "centigrade")),
        Unit("f", Dimension.TEMPERATURE, 5.0 / 9.0, -160.0 / 9.0, aliases = listOf("fahrenheit")),
        // Speed, base kilometres per hour.
        //
        // The slash spellings are the reason this table is consulted before
        // anything else looks at the token. A slash means an ambiguous date
        // in `$ days` and means nothing at all here, which is safe because
        // the two parsers share no code and neither calls the other.
        Unit("kph", Dimension.SPEED, 1.0, aliases = listOf("km/h", "kmh", "kmph")),
        Unit("mph", Dimension.SPEED, 1.609344, aliases = listOf("mi/h")),
    )

    /** Every spelling of every unit. `ConvertTest` asserts none is claimed twice. */
    private val BY_TOKEN: Map<String, Unit> =
        UNITS.flatMap { u -> spellingsOf(u).map { it to u } }.toMap()

    /** A unit's canonical token and its aliases, which is what a reader may type. */
    fun spellingsOf(unit: Unit): List<String> = listOf(unit.token) + unit.aliases

    enum class Error {
        /** A token that is not an admitted unit. */
        UNKNOWN_UNIT,

        /** Both units are real and they measure different things. */
        DIMENSION_MISMATCH,

        /** The amount was not a number. */
        NOT_A_NUMBER,
    }

    sealed interface Result {
        data class Value(val value: Double, val unit: Unit) : Result
        data class Failed(val error: Error) : Result
    }

    /** The unit for [token], case-insensitively, or null. */
    fun unitFor(token: String): Unit? = BY_TOKEN[token.trim().lowercase()]

    /**
     * Convert [amount] from [from] to [to].
     *
     * Both directions of every pair work, because there is no direction in
     * the table: each unit says how to reach its base and the conversion goes
     * through it.
     */
    fun convert(amount: Double, from: Unit, to: Unit): Result {
        if (from.dimension != to.dimension) return Result.Failed(Error.DIMENSION_MISMATCH)
        val base = amount * from.factor + from.offset
        return Result.Value((base - to.offset) / to.factor, to)
    }

    /** [convert], from tokens, for a caller holding three strings. */
    fun convert(amount: Double, fromToken: String, toToken: String): Result {
        val from = unitFor(fromToken) ?: return Result.Failed(Error.UNKNOWN_UNIT)
        val to = unitFor(toToken) ?: return Result.Failed(Error.UNKNOWN_UNIT)
        return convert(amount, from, to)
    }

    /**
     * Words that join two units and mean nothing.
     *
     * Closed and short on purpose. Every word here is one the alias table can
     * never use, and a longer list is a longer list of spellings a unit is
     * forbidden from having.
     *
     * `in` is on it and is also the token for inches, which is the one real
     * collision in this grammar and is settled by [parse] positionally rather
     * than by preferring one reading. See there.
     */
    val NOISE: Set<String> = setOf("to", "in", "into")

    /**
     * Parse and convert `5 km mi`, or `5 kilometers to miles`.
     *
     * ## Three tokens, or four with a joiner in the middle
     * The amount is not an expression: a converter that evaluated
     * `2+3 km mi` would be a calculator wearing a second verb, and the two
     * are separate commands on purpose.
     *
     * ## Why the joiner is found by position and not by lookup
     * `in` means inches and also means "expressed in". Stripping it wherever
     * it appears would break `12 in cm`, and preferring the unit would break
     * `5 km in mi`, so neither reading can win globally.
     *
     * Position settles it without a preference. A three token line is an
     * amount and two units, so `in` there is a unit. A four token line is an
     * amount, a unit, a joiner and a unit, so the word at index two is a
     * joiner. `5 cm in in` is four tokens and converts centimetres to inches,
     * which is the case that would have needed a special rule under any
     * lookup-based scheme and needs none here.
     *
     * Anything else is [Error.NOT_A_NUMBER], which is the shape refusal.
     */
    fun parse(input: String): Result {
        val parts = input.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val tokens = when {
            parts.size == 3 -> parts
            parts.size == 4 && parts[2] in NOISE -> listOf(parts[0], parts[1], parts[3])
            else -> return Result.Failed(Error.NOT_A_NUMBER)
        }
        val amount = tokens[0].toDoubleOrNull() ?: return Result.Failed(Error.NOT_A_NUMBER)
        return convert(amount, tokens[1], tokens[2])
    }
}
