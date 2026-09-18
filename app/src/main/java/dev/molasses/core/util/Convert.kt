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
    )

    /**
     * Every admitted unit.
     *
     * Eight pairs, sixteen directions. Read the rule above before adding a
     * ninth.
     */
    val UNITS: List<Unit> = listOf(
        // Length, base metres.
        Unit("m", Dimension.LENGTH, 1.0),
        Unit("km", Dimension.LENGTH, 1000.0),
        Unit("cm", Dimension.LENGTH, 0.01),
        Unit("mi", Dimension.LENGTH, 1609.344),
        Unit("ft", Dimension.LENGTH, 0.3048),
        Unit("in", Dimension.LENGTH, 0.0254),
        // Mass, base kilograms.
        Unit("kg", Dimension.MASS, 1.0),
        Unit("g", Dimension.MASS, 0.001),
        Unit("lb", Dimension.MASS, 0.45359237),
        Unit("oz", Dimension.MASS, 0.028349523125),
        Unit("st", Dimension.MASS, 6.35029318),
        // Temperature, base Celsius. The reason the table is affine.
        Unit("c", Dimension.TEMPERATURE, 1.0),
        Unit("f", Dimension.TEMPERATURE, 5.0 / 9.0, -160.0 / 9.0),
        // Speed, base kilometres per hour.
        Unit("kph", Dimension.SPEED, 1.0),
        Unit("mph", Dimension.SPEED, 1.609344),
    )

    private val BY_TOKEN: Map<String, Unit> = UNITS.associateBy { it.token }

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
     * Parse and convert `5 km mi`.
     *
     * Three tokens, in that order. The amount is not an expression: a
     * converter that evaluated `2+3 km mi` would be a calculator wearing a
     * second verb, and the two are separate commands on purpose.
     */
    fun parse(input: String): Result {
        val parts = input.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.size != 3) return Result.Failed(Error.NOT_A_NUMBER)
        val amount = parts[0].toDoubleOrNull() ?: return Result.Failed(Error.NOT_A_NUMBER)
        return convert(amount, parts[1], parts[2])
    }
}
