/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4149
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_21_15_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/StableCaptionBlocks;"
private const val EXTENSION_ROW_INTERFACE = $$"Lapp/morphe/extension/youtube/patches/StableCaptionBlocks$Row;"
private const val EXTENSION_WINDOW_INTERFACE = $$"Lapp/morphe/extension/youtube/patches/StableCaptionBlocks$Window;"

/** Shared native view access; importing it does not enable blocks, translation or their settings. */
internal val nativeCaptionViewPatch = patch {
    dependsOn(sharedExtensionPatch, versionCheckPatch, captionWindowMeasurePatch)
    execute {
        if (!is_21_15_or_greater) return@execute
        val cueType = CaptionCueToStringFingerprint().instructionMatches.first().fieldReference.definingClass
        // Row used to measure caption text.
        val rowType = CaptionRowLayoutFingerprint().classDef.type
        mutableClassDefBy(rowType).apply {
            interfaces.add(EXTENSION_ROW_INTERFACE)

            addHelperMethod(
                "patch_getCaptionLayout",
                "Landroid/text/StaticLayout;",
                smali = """
                    invoke-virtual { p0 }, ${CaptionRowLayoutFingerprint().method}
                    move-result-object v0
                    return-object v0
                """
            )

            addHelperMethod(
                "patch_setCaptionText",
                "V",
                parameters = listOf("Ljava/lang/CharSequence;"),
                smali = """
                    invoke-virtual { p0, p1 }, ${CaptionRowSetTextFingerprint().method}
                    return-void
                """
            )
        }

        // Preserve authored native direction/justification and optional balanced block layouts.
        CaptionRowLayoutFactoryFingerprint().matchAll().forEach { match ->
            val newInstanceIndex = match.instructionMatches[0].index
            val constructorIndex = match.instructionMatches[1].index
            val register = match.method.getInstruction<OneRegisterInstruction>(newInstanceIndex).registerA

            match.method.apply {
                replaceInstruction(
                    constructorIndex,
                    "invoke-static/range { v${register + 1} .. v${register + 7} }, " +
                            "$EXTENSION_CLASS->createLayout($CAPTION_STATIC_LAYOUT_PARAMETERS)Landroid/text/StaticLayout;"
                )
                addInstruction(constructorIndex + 1, "move-result-object v$register")
                removeInstruction(newInstanceIndex)
            }
        }

        // Reuse the existing view ABI without enabling optional block presentation.
        getCaptionWindowSetterFingerprint(cueType).let {
            val windowFields = listOf(
                "patch_getCaptionCue" to it.instructionMatches[0],
                "patch_getVisibleCaptionLines" to it.instructionMatches[1],
                "patch_getFullCaptionLines" to it.instructionMatches[3],
            ).map { (name, match) -> name to match.fieldReference } +
                    ("patch_getCaptionMeasureRow" to CaptionWindowConstructorFingerprint()
                        .instructionMatches.first().fieldReference)

            val backgroundColor = CaptionWindowBackgroundColorFingerprint().method
            val backgroundCall = if (backgroundColor.accessFlags and AccessFlags.PRIVATE.value != 0)
                "invoke-direct" else "invoke-virtual"
            mutableClassDefBy(SUBTITLE_WINDOW_VIEW_CLASS).apply {
                interfaces.add(EXTENSION_WINDOW_INTERFACE)

                addHelperMethod(
                    "patch_getCaptionBackgroundColor",
                    "I",
                    smali = """
                        $backgroundCall { p0 }, $backgroundColor
                        move-result v0
                        return v0
                    """
                )

                addHelperMethod(
                    "patch_setCaptionMeasuredSize",
                    "V",
                    parameters = listOf("I", "I"),
                    smali = """
                        invoke-virtual { p0, p1, p2 }, Landroid/view/View;->setMeasuredDimension(II)V
                        return-void
                    """
                )

                windowFields.forEach { (name, field) ->
                    addHelperMethod(
                        name,
                        if (field.type == "Ljava/util/List;") field.type else "Ljava/lang/Object;",
                        smali = """
                            iget-object v0, p0, $field
                            return-object v0
                        """
                    )
                }
            }
        }


        CaptionWindowMeasureFingerprint().method.apply {
            addInstructions(0, """
                invoke-static { p1 }, Lapp/morphe/extension/youtube/patches/CaptionViewportPatch;->captionWidthSpec(I)I
                move-result p1
                invoke-static/range { p0 .. p0 }, Lapp/morphe/extension/youtube/patches/NativeCaptionWindowLayout;->restore(Landroid/view/View;)V
                invoke-static/range { p0 .. p2 }, Lapp/morphe/extension/youtube/patches/NativeCaptionVertical;->prepare(Landroid/view/View;II)V
            """)
            findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
                addInstruction(index,
                    "invoke-static/range { p0 .. p0 }, Lapp/morphe/extension/youtube/patches/CaptionViewportPatch;->applyCaptionOffset(Landroid/view/View;)V")
                addInstruction(index,
                    "invoke-static/range { p0 .. p0 }, Lapp/morphe/extension/youtube/patches/NativeCaptionWindowLayout;->afterMeasure(Landroid/view/View;)V")
            }
        }
    }
}
