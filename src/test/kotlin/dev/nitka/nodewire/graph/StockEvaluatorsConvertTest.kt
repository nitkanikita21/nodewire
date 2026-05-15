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
}
