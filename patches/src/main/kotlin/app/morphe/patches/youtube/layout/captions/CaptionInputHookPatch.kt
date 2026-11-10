package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.patch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch

/** Shares the bounded native input adapter without enabling caption settings or translation. */
internal val captionInputHookPatch = patch {
    dependsOn(sharedExtensionPatch)
    execute {
        Fingerprint(
            returnType = "Ljava/lang/Object;",
            parameters = listOf("Ljava/io/InputStream;", "L"),
            filters = listOf(
                methodCall(smali = "Lorg/xml/sax/InputSource;-><init>(Ljava/io/InputStream;)V"),
                methodCall(smali = "Lorg/xml/sax/XMLReader;->parse(Lorg/xml/sax/InputSource;)V"),
            ),
        ).method.addInstructions(0, """
            invoke-static { p1 }, Lapp/morphe/extension/youtube/patches/TranslatedCaptionPensPatch;->repairInput(Ljava/io/InputStream;)Ljava/io/InputStream;
            move-result-object p1
        """)
    }
}
