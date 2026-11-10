package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.returnEarly
import app.morphe.patcher.string
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.util.cloneMutable
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

internal class NativeCaptionPenFieldFingerprint(attribute: String) : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/util/Deque;", "Lorg/xml/sax/Attributes;", "Ljava/lang/String;"),
    filters = listOf(
        string(attribute),
        methodCall(returnType = "I", location = MatchAfterWithin(4)),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
        opcode(Opcode.IPUT, location = MatchAfterImmediately()),
    ),
)

internal class NativeCaptionSpanBuilderFingerprint : Fingerprint(
    returnType = "Ljava/lang/CharSequence;",
    parameters = listOf("Ljava/lang/String;", "L"),
    filters = listOf(
        methodCall(smali = "Landroid/text/style/StyleSpan;-><init>(I)V"),
        methodCall(smali = "Landroid/text/style/RelativeSizeSpan;-><init>(F)V"),
        methodCall(smali = "Landroid/text/style/ForegroundColorSpan;-><init>(I)V"),
    ),
)

private fun checkAttributeReader(pen: NativeCaptionPenFieldFingerprint, defaultValue: Int) {
    val invoke = pen.instructionMatches[1]
    check(invoke.methodReference.parameterTypes.map { it.toString() } in listOf(
        listOf("Lorg/xml/sax/Attributes;", "I", "Ljava/lang/String;"),
        listOf("Lorg/xml/sax/Attributes;", "I", "[Ljava/lang/String;"),
    )) { "Unexpected native caption attribute reader" }
    val register = (invoke.instruction as FiveRegisterInstruction).registerD
    val previous = pen.method.instructions.take(invoke.index).lastOrNull {
        it.opcode.setsRegister() && it is OneRegisterInstruction && it.registerA == register
    }
    check(previous is NarrowLiteralInstruction && previous.narrowLiteral == defaultValue) {
        "Unexpected native caption attribute default"
    }
}

/** Adds authored font/baseline spans while keeping the native parser, timing and drawing. */
internal val nativeCaptionTypographyPatch = patch {
    dependsOn(sharedExtensionPatch, captionInputHookPatch, nativeCaptionWindowsPatch)
    execute {
        val baseline = NativeCaptionPenFieldFingerprint("of")
        val font = NativeCaptionPenFieldFingerprint("fs")
        val builder = NativeCaptionSpanBuilderFingerprint()
        checkAttributeReader(baseline, 1)
        checkAttributeReader(font, 0)
        val baselineField = baseline.instructionMatches.last().fieldReference
        val fontField = font.instructionMatches.last().fieldReference
        val baselineInvoke = baseline.instructionMatches[1]
        val baselineStore = baseline.instructionMatches.last()
        val baselineRegisters = baselineStore.instruction as TwoRegisterInstruction
        val baselineDefault = (baselineInvoke.instruction as FiveRegisterInstruction).registerD
        val fontStore = font.instructionMatches.last()
        val fontRegisters = fontStore.instruction as TwoRegisterInstruction
        check(font.method == baseline.method && fontStore.index > baselineStore.index
                && fontField.definingClass == baselineField.definingClass)

        // Apply the later edit first so the earlier baseline indices remain valid.
        // Web treats absent/zero fs as inheritance, rather than an explicit default font.
        font.method.addInstructionsWithLabels(
            fontStore.index,
            """
                if-nez v${fontRegisters.registerA}, :caption_font_explicit
                iget v${fontRegisters.registerA}, v${fontRegisters.registerB}, $fontField
            """,
            ExternalLabel("caption_font_explicit", font.method.getInstruction(fontStore.index)),
        )
        // Retain the native attribute reader, using the copied parent baseline as its default.
        if (baselineDefault != baselineRegisters.registerA) {
            baseline.method.addInstructions(baselineStore.index + 1, "const/4 v$baselineDefault, 0x1")
        }
        baseline.method.addInstructions(baselineInvoke.index,
            "iget v$baselineDefault, v${baselineRegisters.registerB}, $baselineField")

        val paintEffect=builder.method.instructions.filter { it.opcode==Opcode.NEW_INSTANCE }.mapNotNull { it.getReference<TypeReference>()?.type }
            .distinct().filter { !it.startsWith("Landroid/") && !it.startsWith("Ljava/") }
            .map { mutableClassDefBy(it) }.single { type ->
                type.superclass=="Landroid/text/style/CharacterStyle;" && type.methods.any { method ->
                    method.name=="updateDrawState" && method.instructions.any {
                        it.getReference<MethodReference>()?.name=="setAlpha"
                    }
                }
            }
        paintEffect.interfaces.add("Lapp/morphe/extension/youtube/patches/NativeCaptionPreferences\$PaintEffect;")
        val outline = Fingerprint(name="<init>", returnType="V",
            superclass="Landroid/text/style/ReplacementSpan;", parameters=listOf("Ljava/lang/Integer;","I"))
        outline.classDef.apply {
            val color=instanceFields.single { it.type=="I" }
            val foreground=instanceFields.single { it.type=="Ljava/lang/Integer;" }
            addHelperMethod("patch_getOutlineForeground","Ljava/lang/Integer;",smali="iget-object v0, p0, $foreground\nreturn-object v0")
            interfaces.add("Lapp/morphe/extension/youtube/patches/NativeCaptionPreferences\$Outline;")
            addHelperMethod("patch_getOutlineColor","I",smali="iget v0, p0, $color\nreturn v0")
            addHelperMethod("patch_withoutForeground","Landroid/text/style/ReplacementSpan;",freeRegisters=3,
                smali="""
                    new-instance v0, $type
                    const/4 v1, 0x0
                    iget v2, p0, $color
                    invoke-direct { v0, v1, v2 }, ${outline.method}
                    return-object v0
                """)
        }
        val nativeWindow=mutableClassDefBy("Lcom/google/android/libraries/youtube/player/subtitles/ui/SubtitleWindowView;")
        // Native color changes only invalidate; source spans also need to be re-bound.
        nativeWindow.methods.filter { it.returnType=="V" && it.parameterTypes.map { p -> p.toString() }==listOf("I")
                && it.accessFlags and AccessFlags.PUBLIC.value != 0 }.forEach { method ->
            method.findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
                method.addInstructions(index,"invoke-virtual { p0 }, Landroid/view/View;->requestLayout()V")
            }
        }
        val background = mutableClassDefBy("Lcom/google/android/libraries/youtube/player/subtitles/ui/SubtitleWindowView;")
            .methods.single { method -> method.returnType == "I" && method.parameterTypes.isEmpty()
                && method.instructions.any { it.getReference<MethodReference>()?.toString() == "Ljava/lang/Integer;->intValue()I" } }
        check(background.implementation!!.registerCount >= 2)
        background.addInstructionsWithLabels(0,"""
            invoke-static { p0 }, Lapp/morphe/extension/youtube/patches/NativeCaptionAppearance;->background(Ljava/lang/Object;)Ljava/lang/Integer;
            move-result-object v0
            if-eqz v0, :caption_native_background
            invoke-virtual { v0 }, Ljava/lang/Integer;->intValue()I
            move-result v0
            return v0
        """,ExternalLabel("caption_native_background",background.getInstruction(0)))
        val ruby = "Lapp/morphe/extension/youtube/patches/NativeCaptionRuby;"
        baseline.method.apply {
            val original = cloneMutable(name = "patch_readCaptionPen")
            baseline.classDef.methods.add(original)
            // The native reader creates the pen locally and stores it in its parser table.
            // Hook immediately before the store so the same instance is keyed extension-side.
            val putIndex = original.instructions.indexOfFirst { instruction ->
                instruction.getReference<MethodReference>()?.let { reference ->
                    reference.name == "put"
                            && reference.definingClass == "Ljava/util/HashMap;"
                } == true
            }
            check(putIndex >= 0) { "Native caption pen reader has no parser table store" }
            val (table, pen) = when (val store = original.getInstruction(putIndex)) {
                is FiveRegisterInstruction -> store.registerC to store.registerE
                is RegisterRangeInstruction -> store.startRegister to store.startRegister + 2
                else -> error("Unexpected native caption pen store")
            }
            original.addInstructions(putIndex, """
                invoke-static { v$pen, v$table, p2 }, $ruby->onPen(Ljava/lang/Object;Ljava/util/Map;Lorg/xml/sax/Attributes;)V
            """)
            removeInstructions(0, instructions.count())
            addInstructions(0, """
                invoke-virtual/range { p0 .. p3 }, $original
                return-void
            """)
        }
        mutableClassDefBy(ruby).methods.single { it.name == "enabled" }.returnEarly(true)

        builder.method.apply {
            check(parameterTypes[1] == baselineField.definingClass && implementation!!.registerCount >= 6)
            val original = cloneMutable(name = "patch_buildCaptionText")
            builder.classDef.methods.add(original)
            builder.classDef.apply {
                interfaces.add("Lapp/morphe/extension/youtube/patches/NativeCaptionRuns\$Builder;")
                val ownFields = original.instructions.mapNotNull { it.getReference<FieldReference>() }
                    .filter { it.definingClass == type }.distinctBy { it.toString() }
                val config = ownFields.single { it.type.startsWith("L") }
                val constructor = methods.single { it.name == "<init>" &&
                    it.parameterTypes.map { value -> value.toString() } == listOf("Z", config.type) }
                val flags = constructor.instructions.mapNotNull { it.getReference<FieldReference>() }
                    .filter { it.definingClass == type && it.type == "Z" }.distinctBy { it.toString() }.single()
                require(ownFields.all { it.toString() == config.toString() || it.toString() == flags.toString() }) {
                    "Native caption formatter depends on source parser state"
                }
                addHelperMethod("patch_createCaptionRenderer",
                    "Lapp/morphe/extension/youtube/patches/NativeCaptionRuns\$Builder;", freeRegisters = 3,
                    smali = """
                        new-instance v0, $type
                        iget-boolean v1, p0, $flags
                        iget-object v2, p0, $config
                        invoke-direct { v0, v1, v2 }, $constructor
                        return-object v0
                    """)
                addHelperMethod("patch_renderCaptionRun", "Ljava/lang/CharSequence;",
                    parameters = listOf("Ljava/lang/String;", "Ljava/lang/Object;", "Ljava/lang/String;"),
                    freeRegisters = 3,
                    smali = """
                        check-cast p2, ${baselineField.definingClass}
                        invoke-direct/range { p0 .. p2 }, $original
                        move-result-object v0
                        iget v1, p2, $fontField
                        iget v2, p2, $baselineField
                        invoke-static { v0, v2, v1 }, Lapp/morphe/extension/youtube/patches/NativeCaptionTypography;->apply(Ljava/lang/CharSequence;II)Ljava/lang/CharSequence;
                        move-result-object v0
                        invoke-static { v0, p2, p3 }, $ruby->applyTranslated(Ljava/lang/CharSequence;Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/CharSequence;
                        move-result-object v0
                        return-object v0
                    """)
            }
            removeInstructions(0, implementation!!.instructions.count())
            addInstructions(0, """
                invoke-direct/range { p0 .. p2 }, $original
                move-result-object v0
                move-object/from16 v1, p2
                iget v2, v1, $fontField
                iget v1, v1, $baselineField
                invoke-static { v0, v1, v2 }, Lapp/morphe/extension/youtube/patches/NativeCaptionTypography;->apply(Ljava/lang/CharSequence;II)Ljava/lang/CharSequence;
                move-result-object v0
                move-object/from16 v1, p2
                invoke-static { v0, v1 }, $ruby->apply(Ljava/lang/CharSequence;Ljava/lang/Object;)Ljava/lang/CharSequence;
                move-result-object v0
                move-object/from16 v1, p0
                move-object/from16 v2, p2
                invoke-static { v0, v1, v2 }, Lapp/morphe/extension/youtube/patches/NativeCaptionRuns;->mark(Ljava/lang/CharSequence;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/CharSequence;
                move-result-object v0
                return-object v0
            """)
        }
    }
}
