package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.util.cloneMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableField

internal class NativeCaptionWindowReaderFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/util/Deque;", "Lorg/xml/sax/Attributes;", "Ljava/lang/String;"),
    filters = listOf(
        string("id"),
        string("wp"),
        methodCall(parameters = listOf("I"), returnType = "L", location = MatchAfterWithin(6)),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        opcode(Opcode.IPUT_OBJECT, location = MatchAfterImmediately()),
        string("ws"),
        string("rc"),
        string("cc"),
    ),
)

/** Reuses native position copies and native timelines; no window renderer or clock. */
internal val nativeCaptionWindowsPatch = patch {
    dependsOn(sharedExtensionPatch, nativeCaptionViewPatch)
    execute {
        val reader = NativeCaptionWindowReaderFingerprint()
        val lookup = reader.instructionMatches[2].methodReference
        val position = reader.instructionMatches[4].fieldReference
        val positionReader = Fingerprint(
            returnType = "V",
            parameters = listOf("Ljava/util/Deque;", "Lorg/xml/sax/Attributes;", "Ljava/lang/String;"),
            filters = listOf(
                string("id"),
                methodCall(returnType = "I", location = MatchAfterWithin(4)),
                opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
                opcode(Opcode.IPUT, location = MatchAfterImmediately()),
                string("ap"),
            ),
        )
        val positionId = positionReader.instructionMatches[3].fieldReference
        check(position.type == positionId.definingClass && positionId.type == "I")
        check(mutableClassDefBy(position.type).methods.any {
            it.name == "<init>" && it.parameterTypes.map { type -> type.toString() } == listOf(position.type)
        }) { "Native caption position copy constructor missing" }

        val extension = "Lapp/morphe/extension/youtube/patches/NativeCaptionWindows;"
        val styleReader = NativeCaptionPenFieldFingerprint("mh")
        val styleType = styleReader.instructionMatches.last().fieldReference.definingClass
        val definition = "Lapp/morphe/extension/youtube/patches/NativeCaptionWindowData\$Definition;"
        val source = "Lapp/morphe/extension/youtube/patches/NativeCaptionWindowData\$Source;"
        mutableClassDefBy(position.type).apply {
            interfaces.add("Lapp/morphe/extension/youtube/patches/NativeCaptionWindows\$Position;")
            val metadata = "$type->patch_windowPosition:$definition"
            instanceFields.add(ImmutableField(type, "patch_windowPosition", definition,
                AccessFlags.PRIVATE.value, null, null, null).toMutable())
            addHelperMethod("patch_getWindowPosition", definition,
                smali = "iget-object v0, p0, $metadata\nreturn-object v0")
            addHelperMethod("patch_setWindowPosition", "V", parameters = listOf(definition),
                smali = "iput-object p1, p0, $metadata\nreturn-void")
        }
        mutableClassDefBy(styleType).apply {
            interfaces.add("Lapp/morphe/extension/youtube/patches/NativeCaptionWindows\$Style;")
            val metadata = "$type->patch_windowStyle:$definition"
            instanceFields.add(ImmutableField(type, "patch_windowStyle", definition,
                AccessFlags.PRIVATE.value, null, null, null).toMutable())
            addHelperMethod("patch_getWindowStyle", definition,
                smali = "iget-object v0, p0, $metadata\nreturn-object v0")
            addHelperMethod("patch_setWindowStyle", "V", parameters = listOf(definition),
                smali = "iput-object p1, p0, $metadata\nreturn-void")
        }
        mutableClassDefBy(lookup.definingClass).apply {
            interfaces.add("Lapp/morphe/extension/youtube/patches/NativeCaptionWindows\$Parser;")
            val positions = methods.single { it.name == lookup.name && it.parameterTypes == lookup.parameterTypes }
                .instructions.mapNotNull { it.getReference<FieldReference>() }
                .single { it.definingClass == type && it.type == "Ljava/util/HashMap;" }
            val windows = methods.single {
                it.returnType == position.definingClass && it.parameterTypes.map { type -> type.toString() } == listOf("I")
            }.instructions.mapNotNull { it.getReference<FieldReference>() }
                .single { it.definingClass == type && it.type == "Ljava/util/HashMap;" }
            addHelperMethod("patch_getWindowPositions", "Ljava/util/Map;",
                smali = "iget-object v0, p0, $positions\nreturn-object v0")
            addHelperMethod("patch_getWindowDefinitions", "Ljava/util/Map;",
                smali = "iget-object v0, p0, $windows\nreturn-object v0")
            val styles = methods.single { it.returnType == styleType
                    && it.parameterTypes.map { type -> type.toString() } == listOf("I") }
                .instructions.mapNotNull { it.getReference<FieldReference>() }
                .single { it.definingClass == type && it.type == "Ljava/util/HashMap;" }
            addHelperMethod("patch_getWindowStyles", "Ljava/util/Map;",
                smali = "iget-object v0, p0, $styles\nreturn-object v0")
            val constructorParameters = listOf("I", "Ljava/lang/Integer;", "Ljava/lang/Integer;")
            fun MethodReference.isTextSettingsConstructor() = name == "<init>"
                    && parameterTypes.map { it.toString() } == constructorParameters
            val assembler = methods.single { method -> method.returnType == "V"
                    && method.parameterTypes.size == 1
                    && method.instructions.any { it.getReference<MethodReference>()?.isTextSettingsConstructor() == true } }
            val paragraphType = assembler.parameterTypes.single().toString()
            mutableClassDefBy(paragraphType).apply {
                val style = instanceFields.single { it.type == styleType }
                interfaces.add("Lapp/morphe/extension/youtube/patches/NativeCaptionWindows\$Paragraph;")
                addHelperMethod("patch_getWindowStyle", "Ljava/lang/Object;",
                    smali = "iget-object v0, p0, $style\nreturn-object v0")
                val geometry = instanceFields.single { it.type == position.type }
                addHelperMethod("patch_getWindowPosition", "Ljava/lang/Object;",
                    smali = "iget-object v0, p0, $geometry\nreturn-object v0")
            }
            val site = assembler.instructions.withIndex().single {
                it.value.getReference<MethodReference>()?.isTextSettingsConstructor() == true
            }
            val alignment = when (val instruction = site.value) {
                is FiveRegisterInstruction -> instruction.registerD
                is RegisterRangeInstruction -> instruction.startRegister + 1
                else -> error("Unsupported native text settings constructor call")
            }
            val settingsType = site.value.getReference<MethodReference>()!!.definingClass
            mutableClassDefBy(settingsType).apply {
                interfaces.add("Lapp/morphe/extension/youtube/patches/NativeCaptionWindows\$Settings;")
                val metadata = "$type->patch_windowSource:$source"
                instanceFields.add(ImmutableField(type, "patch_windowSource", source,
                    AccessFlags.PRIVATE.value, null, null, null).toMutable())
                addHelperMethod("patch_getWindowSource", source,
                    smali = "iget-object v0, p0, $metadata\nreturn-object v0")
                addHelperMethod("patch_setWindowSource", "V", parameters = listOf(source),
                    smali = "iput-object p1, p0, $metadata\nreturn-void")
                methods.single { it.name == "equals" && it.parameterTypes.map { type -> type.toString() } == listOf("Ljava/lang/Object;") }.apply {
                    check(implementation!!.registerCount >= 3)
                    val original = cloneMutable(name = "patch_nativeTextSettingsEquals")
                    methods.add(original)
                    removeInstructions(0, instructions.count())
                    addInstructions(0, """
                        invoke-virtual/range { p0 .. p1 }, $original
                        move-result v0
                        if-eqz v0, :done
                        invoke-static/range { p0 .. p1 }, $extension->sameSettings(Ljava/lang/Object;Ljava/lang/Object;)Z
                        move-result v0
                        :done
                        return v0
                    """)
                }
                methods.single { it.name == "hashCode" && it.parameterTypes.isEmpty() }.apply {
                    check(implementation!!.registerCount >= 3)
                    val original = cloneMutable(name = "patch_nativeTextSettingsHash")
                    methods.add(original)
                    removeInstructions(0, instructions.count())
                    addInstructions(0, """
                        invoke-virtual/range { p0 .. p0 }, $original
                        move-result v0
                        move-object/from16 v1, p0
                        invoke-static { v0, v1 }, $extension->settingsHash(ILjava/lang/Object;)I
                        move-result v0
                        return v0
                    """)
                }
            }
            check(alignment < assembler.implementation!!.registerCount - 2)
            val default = assembler.instructions.take(site.index).lastOrNull {
                it.opcode.setsRegister() && it is OneRegisterInstruction && it.registerA == alignment
            }
            check(default is NarrowLiteralInstruction && default.narrowLiteral == 0) {
                "Native caption alignment no longer defaults to anchor alignment"
            }
            // Borrow only the verified zero argument, then restore it for the remaining code.
            val instance = when (val instruction = site.value) {
                is FiveRegisterInstruction -> instruction.registerC
                is RegisterRangeInstruction -> instruction.startRegister
                else -> error("Unsupported native text settings constructor call")
            }
            val bind = if (site.value is RegisterRangeInstruction)
                "invoke-static/range { v$instance .. v$alignment }"
            else "invoke-static { v$instance, v$alignment }"
            assembler.addInstructions(site.index + 1, """
                move-object/from16 v$alignment, p1
                $bind, $extension->bindSettings(Ljava/lang/Object;Ljava/lang/Object;)V
                const/16 v$alignment, 0x0
            """)
            assembler.addInstructions(site.index, """
                invoke-static/range { p1 .. p1 }, $extension->alignment(Ljava/lang/Object;)I
                move-result v$alignment
            """)

            val formattingType = assembler.instructions.mapNotNull { it.getReference<MethodReference>() }
                .single { it.name == "<init>" && it.parameterTypes.map { type -> type.toString() } ==
                        listOf("I", "I", "I", "Z", "Z", settingsType) }.definingClass
            val cue = Fingerprint(name = "<init>", returnType = "V",
                parameters = listOf("I", "J", "Ljava/lang/CharSequence;", "Ljava/lang/CharSequence;", formattingType)).classDef
            val cueSettings = cue.instanceFields.single { it.type == formattingType }
            val textSettings = mutableClassDefBy(formattingType).instanceFields.single { it.type == settingsType }
            mutableClassDefBy("Lcom/google/android/libraries/youtube/player/subtitles/ui/SubtitleWindowView;").apply {
                val currentCue = instanceFields.single { it.type == cue.type }
                interfaces.add("Lapp/morphe/extension/youtube/patches/NativeCaptionWindows\$Host;")
                addHelperMethod("patch_getWindowSource", source, smali = """
                    iget-object v0, p0, $currentCue
                    if-eqz v0, :empty
                    iget-object v0, v0, $cueSettings
                    iget-object v0, v0, $textSettings
                    if-eqz v0, :empty
                    invoke-virtual { v0 }, $settingsType->patch_getWindowSource()$source
                    move-result-object v0
                    return-object v0
                    :empty
                    const/4 v0, 0x0
                    return-object v0
                """)
                // Preserve the original setter and its fingerprints for the other patches.
                val setter = getCaptionWindowSetterFingerprint(cue.type)
                setter.method.apply {
                    val refresh = instructions.withIndex().single {
                        it.value.getReference<MethodReference>()?.name == "requestLayout"
                    }
                    addInstructions(refresh.index, "invoke-static/range { p0 .. p0 }, $extension->onCue(Ljava/lang/Object;)V")
                }
            }
        }
        mutableClassDefBy(position.definingClass).apply {
            interfaces.add("Lapp/morphe/extension/youtube/patches/NativeCaptionWindows\$Window;")
            addHelperMethod("patch_setNativeWindowId", "V", parameters = listOf("I"), freeRegisters = 2,
                smali = """
                    new-instance v0, ${position.type}
                    iget-object v1, p0, $position
                    invoke-direct { v0, v1 }, ${position.type}-><init>(${position.type})V
                    iput p1, v0, $positionId
                    invoke-virtual { v1 }, ${position.type}->patch_getWindowPosition()$definition
                    move-result-object v1
                    invoke-virtual { v0, v1 }, ${position.type}->patch_setWindowPosition($definition)V
                    iput-object v0, p0, $position
                    return-void
                """)
        }
        reader.method.apply {
            check(implementation!!.registerCount >= 4)
            val original = cloneMutable(name = "patch_readCaptionWindow")
            reader.classDef.methods.add(original)
            removeInstructions(0, instructions.count())
            addInstructions(0, """
                invoke-virtual/range { p0 .. p3 }, $original
                invoke-static/range { p1 .. p2 }, $extension->onWindow(Ljava/util/Deque;Lorg/xml/sax/Attributes;)V
                return-void
            """)
        }
        styleReader.method.apply {
            check(implementation!!.registerCount >= 4)
            val original = cloneMutable(name = "patch_readCaptionWindowStyle")
            styleReader.classDef.methods.add(original)
            removeInstructions(0, instructions.count())
            addInstructions(0, """
                invoke-virtual/range { p0 .. p3 }, $original
                invoke-static/range { p1 .. p2 }, $extension->onStyle(Ljava/util/Deque;Lorg/xml/sax/Attributes;)V
                return-void
            """)
        }
        positionReader.method.apply {
            check(implementation!!.registerCount >= 4)
            val original = cloneMutable(name = "patch_readCaptionWindowPosition")
            positionReader.classDef.methods.add(original)
            removeInstructions(0, instructions.count())
            addInstructions(0, """
                invoke-virtual/range { p0 .. p3 }, $original
                invoke-static/range { p1 .. p2 }, $extension->onPosition(Ljava/util/Deque;Lorg/xml/sax/Attributes;)V
                return-void
            """)
        }
    }
}
