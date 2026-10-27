package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION = "Lapp/morphe/extension/youtube/patches/TranslatedCaptionHardBreakPatch;"

/**
 * Only the tested 21.23.492 ABI is used. The default Morphe caption patch
 * already identifies translated requests on their parser thread.
 */
@Suppress("unused")
val translatedCaptionHardBreakPatch = bytecodePatch(
    name = "Test: Fix auto-translated caption line breaks",
    description = "Flattens hard source newlines in translated scrolling captions; " +
            "lets the original YouTube renderer wrap the text at its actual width.",
    default = false,
) {
    dependsOn(captionsPatch)
    compatibleWith(
        Compatibility(
            packageName = "com.google.android.youtube",
            name = "YouTube",
            targets = listOf(AppTarget(version = "21.23.492", minSdk = 28, isExperimental = true)),
        )
    )

    execute {
        require(packageMetadata.versionName == "21.23.492") {
            "Translated caption line-break experiment is supported only for 21.23.492"
        }
        settingsMenuCaptionGroup.add(
            SwitchPreference("morphe_translated_captions_flatten_breaks_test", summary = true)
        )

        // Preserve source id/time/positioning while replacing only the sampled text.
        mutableClassDefBy("Lapoo;").apply {
            interfaces.add("${EXTENSION.removeSuffix(";")}\$Cue;")
            addHelperMethod(
                "patch_getVisible", "Ljava/lang/CharSequence;",
                smali = "iget-object v0, p0, Lapoo;->d:Ljava/lang/CharSequence;\nreturn-object v0",
            )
            addHelperMethod(
                "patch_getFull", "Ljava/lang/CharSequence;",
                smali = "iget-object v0, p0, Lapoo;->e:Ljava/lang/CharSequence;\nreturn-object v0",
            )
            addHelperMethod(
                "patch_isScrollable", "Z",
                smali = """
                    iget-object v0, p0, Lapoo;->c:Lapol;
                    iget-boolean v0, v0, Lapol;->f:Z
                    return v0
                """.trimIndent(),
            )
            addHelperMethod(
                "patch_withTexts", "Ljava/lang/Object;",
                parameters = listOf("Ljava/lang/CharSequence;", "Ljava/lang/CharSequence;"),
                freeRegisters = 7,
                smali = """
                    new-instance v0, Lapoo;
                    iget v1, p0, Lapoo;->a:I
                    iget-wide v2, p0, Lapoo;->b:J
                    move-object v4, p1
                    move-object v5, p2
                    iget-object v6, p0, Lapoo;->c:Lapol;
                    invoke-direct/range { v0 .. v6 }, Lapoo;-><init>(IJLjava/lang/CharSequence;Ljava/lang/CharSequence;Lapol;)V
                    return-object v0
                """.trimIndent(),
            )
        }

        // Track provenance on the parsed timeline, never in mutable global UI state.
        mutableClassDefBy("Lapnt;").apply {
            val translatedField = "Lapnt;->morpheCaptionAutoTranslated:Z"
            instanceFields.add(
                ImmutableField(
                    type, "morpheCaptionAutoTranslated", "Z",
                    AccessFlags.PRIVATE.value, null, null, null,
                ).toMutable()
            )
            methods.single {
                it.name == "<init>" && it.parameterTypes == listOf("Ljava/util/List;")
            }.apply {
                val index = implementation!!.instructions.indexOfLast { it.opcode == Opcode.RETURN_VOID }
                require(index >= 0) { "Native caption timeline constructor has no return" }
                addInstructions(
                    index,
                    """
                        invoke-static {}, $EXTENSION->captureTranslation()Z
                        move-result v0
                        iput-boolean v0, p0, $translatedField
                    """.trimIndent(),
                )
            }

            // Native optimized code reuses p0 before returning. A wrapper is mandatory:
            // take the actual native sampled frame first, then normalize only when safe.
            val sampler = methods.single {
                it.name == "c" &&
                    it.parameterTypes == listOf("J") &&
                    it.returnType == "Ljava/util/List;"
            }
            val nativeFlags = sampler.accessFlags
            sampler.name = "morpheOriginalSampleForBreaks"
            methods.add(
                ImmutableMethod(
                    type, "c", listOf(ImmutableMethodParameter("J", null, null)),
                    "Ljava/util/List;", nativeFlags, null, null,
                    MutableMethodImplementation(5),
                ).toMutable().apply {
                    addInstructions(
                        0,
                        """
                            invoke-virtual/range { p0 .. p2 }, Lapnt;->morpheOriginalSampleForBreaks(J)Ljava/util/List;
                            move-result-object v0
                            iget-boolean v1, p0, $translatedField
                            invoke-static { v0, v1 }, $EXTENSION->normalize(Ljava/util/List;Z)Ljava/util/List;
                            move-result-object v0
                            return-object v0
                        """.trimIndent(),
                    )
                }
            )
        }
    }
}
