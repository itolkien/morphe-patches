package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableField

private const val LAYOUT = "Lapp/morphe/extension/youtube/patches/TranslatedCaptionLayoutPatch;"
private const val POLICY = "Lapp/morphe/extension/youtube/patches/CaptionReflowPolicy;"

/** This experiment deliberately uses audited 21.23.492 ABI names, failing on all other versions. */
@Suppress("unused")
val translatedCaptionLayoutPatch = bytecodePatch(
    name = "Reflow auto-translated caption windows (test)",
    description = "Experimental translated-only window reflow and safe source font sizes. YouTube 21.23.492 only.",
    default = false,
) {
    dependsOn(captionsPatch)
    compatibleWith(Compatibility(packageName = "com.google.android.youtube", name = "YouTube", targets = listOf(AppTarget(version = "21.23.492", minSdk = 28, isExperimental = true))))
    execute {
        require(packageMetadata.versionName == "21.23.492") { "Caption layout replay requires the audited 21.23.492 ABI" }
        settingsMenuCaptionGroup.add(SwitchPreference("morphe_translated_captions_reflow_test", summary = true))
        mutableClassDefBy(LAYOUT).methods.single { it.name == "isIncluded" }.returnEarly(true)

        // The source pen is resolved normally, but only its relative font size and safe emphasis survive.
        // This avoids reviving invisible source colors/opacity while keeping the native size.
        mutableClassDefBy("Lapny;").apply {
            require(instanceFields.any { it.name == "g" && it.type == "I" })
            interfaces.add("${LAYOUT.removeSuffix(";")}\$Pen;")
            addHelperMethod("patch_copySizeOnly", "Ljava/lang/Object;", freeRegisters = 2, smali = """
                new-instance v0, Lapny;
                invoke-direct { v0 }, Lapny;-><init>()V
                iget v1, p0, Lapny;->g:I
                iput v1, v0, Lapny;->g:I
                iget-boolean v1, p0, Lapny;->c:Z
                iput-boolean v1, v0, Lapny;->c:Z
                iget-boolean v1, p0, Lapny;->d:Z
                iput-boolean v1, v0, Lapny;->d:Z
                iget-boolean v1, p0, Lapny;->e:Z
                iput-boolean v1, v0, Lapny;->e:Z
                return-object v0
            """)
        }
        mutableClassDefBy("Lapnv;").methods.single { it.name == "c" && it.parameterTypes == listOf("I") }.apply {
            implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }.reversed().forEach { (index, ins) ->
                val reg = (ins as OneRegisterInstruction).registerA
                addInstructions(index, """
                    invoke-static { v$reg }, $LAYOUT->safePen(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v$reg
                    check-cast v$reg, Lapny;
                """)
            }
        }

        mutableClassDefBy("Lapoo;").apply {
            interfaces.add("${POLICY.removeSuffix(";")}\$Cue;")
            addHelperMethod("patch_getId", "I", smali = "iget v0, p0, Lapoo;->a:I\nreturn v0")
            addHelperMethod("patch_getTime", "J", freeRegisters = 2, smali = "iget-wide v0, p0, Lapoo;->b:J\nreturn-wide v0")
            addHelperMethod("patch_getText", "Ljava/lang/CharSequence;", smali = "iget-object v0, p0, Lapoo;->d:Ljava/lang/CharSequence;\nreturn-object v0")
            for ((name, field, type) in listOf(
                Triple("patch_getX", "c", "I"), Triple("patch_getY", "d", "I"),
                Triple("patch_isVisible", "e", "Z"), Triple("patch_isScrollable", "f", "Z"))) {
                addHelperMethod(name, type, smali = """
                    iget-object v0, p0, Lapoo;->c:Lapol;
                    ${if (type == "Z") "iget-boolean" else "iget"} v0, v0, Lapol;->$field:$type
                    return v0
                """)
            }
            addHelperMethod("patch_withText", "Ljava/lang/Object;", parameters = listOf("Ljava/lang/CharSequence;"), freeRegisters = 7, smali = """
                new-instance v0, Lapoo;
                const v1, -0x80000000
                iget-wide v2, p0, Lapoo;->b:J
                move-object v4, p1
                move-object v5, p1
                sget-object v6, Lapol;->a:Lapol;
                invoke-direct/range { v0 .. v6 }, Lapoo;-><init>(IJLjava/lang/CharSequence;Ljava/lang/CharSequence;Lapol;)V
                return-object v0
            """)
        }

        // Capture provenance on the parsed timeline. A UI-thread global flag would mix tracks.
        mutableClassDefBy("Lapnt;").apply {
            val field = "Lapnt;->morpheTranslated:Z"
            instanceFields.add(ImmutableField(type, "morpheTranslated", "Z", AccessFlags.PRIVATE.value, null, null, null).toMutable())
            methods.single { it.name == "<init>" && it.parameterTypes == listOf("Ljava/util/List;") }.apply {
                val index = implementation!!.instructions.indexOfLast { it.opcode == Opcode.RETURN_VOID }
                addInstructions(index, """
                    invoke-static { }, $LAYOUT->captureTranslatedTrack()Z
                    move-result v0
                    iput-boolean v0, p0, $field
                """)
            }
            val sample = methods.single { it.name == "c" && it.parameterTypes == listOf("J") && it.returnType == "Ljava/util/List;" }
            // Native c(J) reuses its p0 register as an integer local before returning.
            // Wrap it instead of injecting at its labelled returns or trusting p0 there.
            sample.name = "morpheSampleOriginal"
            methods.add(ImmutableMethod(
                type, "c", listOf(ImmutableMethodParameter("J", null, null)), "Ljava/util/List;",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                // v0/v1 are locals; p0 is the receiver and p1/p2 hold the wide sample time.
                MutableMethodImplementation(5),
            ).toMutable().apply {
                addInstructions(0, """
                    invoke-virtual/range { p0 .. p2 }, Lapnt;->morpheSampleOriginal(J)Ljava/util/List;
                    move-result-object v0
                    iget-boolean v1, p0, $field
                    invoke-static { v0, v1 }, $LAYOUT->reflow(Ljava/util/List;Z)Ljava/util/List;
                    move-result-object v0
                    return-object v0
                """)
            })
        }
    }
}
