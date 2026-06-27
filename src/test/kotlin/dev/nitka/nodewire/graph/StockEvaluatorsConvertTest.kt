package dev.nitka.nodewire.graph

import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StockEvaluatorsConvertTest {

    private fun cfg(build: CompoundTag.() -> Unit): CompoundTag =
        CompoundTag().apply(build)

    @Test fun intToFloat() = assertEquals(
        PinValue.Float(5f),
        StockEvaluators.Convert(
            cfg { putString("sourceType", "INT"); putString("targetType", "FLOAT") },
            mapOf("in" to PinValue.Int(5)),
        )["out"],
    )

    @Test fun floatToInt() = assertEquals(
        PinValue.Int(3),
        StockEvaluators.Convert(
            cfg { putString("sourceType", "FLOAT"); putString("targetType", "INT") },
            mapOf("in" to PinValue.Float(3.7f)),
        )["out"],
    )

    @Test fun boolToInt() = assertEquals(
        PinValue.Int(1),
        StockEvaluators.Convert(
            cfg { putString("sourceType", "BOOL"); putString("targetType", "INT") },
            mapOf("in" to PinValue.Bool(true)),
        )["out"],
    )

    @Test fun intToBool() = assertEquals(
        PinValue.Bool(true),
        StockEvaluators.Convert(
            cfg { putString("sourceType", "INT"); putString("targetType", "BOOL") },
            mapOf("in" to PinValue.Int(7)),
        )["out"],
    )

    @Test fun intToBoolZeroIsFalse() = assertEquals(
        PinValue.Bool(false),
        StockEvaluators.Convert(
            cfg { putString("sourceType", "INT"); putString("targetType", "BOOL") },
            mapOf("in" to PinValue.Int(0)),
        )["out"],
    )

    @Test fun intToRedstoneClamp() = assertEquals(
        PinValue.Redstone(15),
        StockEvaluators.Convert(
            cfg {
                putString("sourceType","INT"); putString("targetType","REDSTONE")
                putString("mode","clamp")
            },
            mapOf("in" to PinValue.Int(20)),
        )["out"],
    )
    @Test fun intToRedstoneModulo() = assertEquals(
        PinValue.Redstone(4),
        StockEvaluators.Convert(
            cfg {
                putString("sourceType","INT"); putString("targetType","REDSTONE")
                putString("mode","modulo")
            },
            mapOf("in" to PinValue.Int(20)),
        )["out"],
    )
    @Test fun intToRedstoneThreshold() = assertEquals(
        PinValue.Redstone(15),
        StockEvaluators.Convert(
            cfg {
                putString("sourceType","INT"); putString("targetType","REDSTONE")
                putString("mode","threshold"); putInt("threshold",5)
            },
            mapOf("in" to PinValue.Int(7)),
        )["out"],
    )
    @Test fun intToRedstoneScaled() = assertEquals(
        PinValue.Redstone(15),
        StockEvaluators.Convert(
            cfg {
                putString("sourceType","INT"); putString("targetType","REDSTONE")
                putString("mode","scaled"); putInt("min",0); putInt("max",100)
            },
            mapOf("in" to PinValue.Int(100)),
        )["out"],
    )
    @Test fun floatToRedstoneThreshold() = assertEquals(
        PinValue.Redstone(0),
        StockEvaluators.Convert(
            cfg {
                putString("sourceType","FLOAT"); putString("targetType","REDSTONE")
                putString("mode","threshold"); putFloat("thresholdF",1f)
            },
            mapOf("in" to PinValue.Float(0.5f)),
        )["out"],
    )
    @Test fun floatToRedstoneScaled() = assertEquals(
        PinValue.Redstone(15),
        StockEvaluators.Convert(
            cfg {
                putString("sourceType","FLOAT"); putString("targetType","REDSTONE")
                putString("mode","scaled"); putFloat("minF",0f); putFloat("maxF",1f)
            },
            mapOf("in" to PinValue.Float(1f)),
        )["out"],
    )
    @Test fun boolToRedstoneHi() = assertEquals(
        PinValue.Redstone(15),
        StockEvaluators.Convert(
            cfg {
                putString("sourceType","BOOL"); putString("targetType","REDSTONE")
                putString("mode","hi")
            },
            mapOf("in" to PinValue.Bool(true)),
        )["out"],
    )
    @Test fun boolToRedstoneLevel() = assertEquals(
        PinValue.Redstone(7),
        StockEvaluators.Convert(
            cfg {
                putString("sourceType","BOOL"); putString("targetType","REDSTONE")
                putString("mode","level"); putInt("level",7)
            },
            mapOf("in" to PinValue.Bool(true)),
        )["out"],
    )
    @Test fun redstoneToIntRaw() = assertEquals(
        PinValue.Int(7),
        StockEvaluators.Convert(
            cfg {
                putString("sourceType","REDSTONE"); putString("targetType","INT")
                putString("mode","raw")
            },
            mapOf("in" to PinValue.Redstone(7)),
        )["out"],
    )
    @Test fun redstoneToIntScaled() = assertEquals(
        PinValue.Int(100),
        StockEvaluators.Convert(
            cfg {
                putString("sourceType","REDSTONE"); putString("targetType","INT")
                putString("mode","scaled"); putInt("min",0); putInt("max",100)
            },
            mapOf("in" to PinValue.Redstone(15)),
        )["out"],
    )
    @Test fun redstoneToFloatNormalized() = assertEquals(
        PinValue.Float(1f),
        StockEvaluators.Convert(
            cfg {
                putString("sourceType","REDSTONE"); putString("targetType","FLOAT")
                putString("mode","normalized")
            },
            mapOf("in" to PinValue.Redstone(15)),
        )["out"],
    )
    @Test fun redstoneToFloatRaw() = assertEquals(
        PinValue.Float(7f),
        StockEvaluators.Convert(
            cfg {
                putString("sourceType","REDSTONE"); putString("targetType","FLOAT")
                putString("mode","raw")
            },
            mapOf("in" to PinValue.Redstone(7)),
        )["out"],
    )
    @Test fun redstoneToFloatScaled() = assertEquals(
        PinValue.Float(1f),
        StockEvaluators.Convert(
            cfg {
                putString("sourceType","REDSTONE"); putString("targetType","FLOAT")
                putString("mode","scaled"); putFloat("minF",-1f); putFloat("maxF",1f)
            },
            mapOf("in" to PinValue.Redstone(15)),
        )["out"],
    )
    @Test fun redstoneToBoolAny() = assertEquals(
        PinValue.Bool(true),
        StockEvaluators.Convert(
            cfg {
                putString("sourceType","REDSTONE"); putString("targetType","BOOL")
                putString("mode","any")
            },
            mapOf("in" to PinValue.Redstone(1)),
        )["out"],
    )
    @Test fun redstoneToBoolThreshold() = assertEquals(
        PinValue.Bool(false),
        StockEvaluators.Convert(
            cfg {
                putString("sourceType","REDSTONE"); putString("targetType","BOOL")
                putString("mode","threshold"); putInt("threshold",8)
            },
            mapOf("in" to PinValue.Redstone(7)),
        )["out"],
    )

    // ── scaled-mode MIDPOINT cases (caught the truncation bug) ─────────────
    // The endpoint cases above pass even with the old `.toInt()` truncation;
    // these mid-range cases are where truncating-before-offset pinned the
    // result to the low end (e.g. signal<8 always gave min). They lock in the
    // round-the-whole-value fix.

    private fun rsToIntScaled(min: Int, max: Int, signal: Int) = StockEvaluators.Convert(
        cfg {
            putString("sourceType","REDSTONE"); putString("targetType","INT")
            putString("mode","scaled"); putInt("min",min); putInt("max",max)
        },
        mapOf("in" to PinValue.Redstone(signal)),
    )["out"]

    @Test fun redstoneToIntScaledFullLeft() = assertEquals(PinValue.Int(-1), rsToIntScaled(-1, 1, 0))
    @Test fun redstoneToIntScaledMidpointIsZero() = assertEquals(PinValue.Int(0), rsToIntScaled(-1, 1, 7))
    @Test fun redstoneToIntScaledFullRight() = assertEquals(PinValue.Int(1), rsToIntScaled(-1, 1, 15))

    @Test fun intToRedstoneScaledRoundsMidpoint() = assertEquals(
        // 1 of [0,2] -> 7.5 -> rounds to 8 (old truncation gave 7).
        PinValue.Redstone(8),
        StockEvaluators.Convert(
            cfg {
                putString("sourceType","INT"); putString("targetType","REDSTONE")
                putString("mode","scaled"); putInt("min",0); putInt("max",2)
            },
            mapOf("in" to PinValue.Int(1)),
        )["out"],
    )
}
