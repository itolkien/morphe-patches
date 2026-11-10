package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/CaptionCookiesPatch;"

internal val captionCookiesPatch = patch(
    description = "Adds an option to set cookies in Timed Text API (Caption Data API) requests.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    execute {
        settingsMenuCaptionGroup.addAll(listOf(
            SwitchPreference("morphe_set_caption_cookies", summary = true),
            TextPreference(
                "morphe_caption_cookies",
                inputType = InputType.TEXT_MULTI_LINE
            ),
            NonInteractivePreference(
                key = "morphe_get_caption_cookies",
                tag = "app.morphe.extension.youtube.settings.preference.GetCaptionCookiesPreference",
            )
        ))

        TimedTextUrlFingerprint.let {
            it.clearMatch() // Fingerprint is shared and indexes may no longer be correct.
            it.method.apply {
                val helperMethod = ImmutableMethod(
                    definingClass,
                    "patch_newCaptionUrlRequestBuilder",
                    listOf(
                        "Lorg/chromium/net/CronetEngine;",
                        "Ljava/lang/String;",
                        $$"Lorg/chromium/net/UrlRequest$Callback;",
                        "Ljava/util/concurrent/Executor;"
                    ).map { ImmutableMethodParameter(it, null, null) },
                    $$"Lorg/chromium/net/UrlRequest$Builder;",
                    AccessFlags.PRIVATE.value or AccessFlags.STATIC.value,
                    null,
                    null,
                    MutableMethodImplementation(7),
                ).toMutable().apply {
                    addInstructionsWithLabels(
                        0,
                        $$"""
                            invoke-virtual { p0, p1, p2, p3 }, Lorg/chromium/net/CronetEngine;->newUrlRequestBuilder(Ljava/lang/String;Lorg/chromium/net/UrlRequest$Callback;Ljava/util/concurrent/Executor;)Lorg/chromium/net/UrlRequest$Builder;
                            move-result-object v0
                            invoke-static { p1 }, $$EXTENSION_CLASS->shouldSetCookies(Ljava/lang/String;)Z
                            move-result v1
                            if-eqz v1, :disabled
                            
                            # Set Cookie.
                            const-string v1, "Cookie"
                            invoke-static { }, $$EXTENSION_CLASS->getCookies()Ljava/lang/String;
                            move-result-object v2
                            invoke-virtual { v0, v1, v2 }, Lorg/chromium/net/UrlRequest$Builder;->addHeader(Ljava/lang/String;Ljava/lang/String;)Lorg/chromium/net/UrlRequest$Builder;

                            # Set User-Agent.
                            const-string v1, "User-Agent"
                            invoke-static { }, $$EXTENSION_CLASS->getUserAgent()Ljava/lang/String;
                            move-result-object v2

                            # Set Header.
                            invoke-virtual { v0, v1, v2 }, Lorg/chromium/net/UrlRequest$Builder;->addHeader(Ljava/lang/String;Ljava/lang/String;)Lorg/chromium/net/UrlRequest$Builder;

                            :disabled
                            return-object v0
                        """,
                    )
                }

                it.classDef.methods.add(helperMethod)

                val urlIndex = it.instructionMatches[0].index
                val call = getInstruction<FiveRegisterInstruction>(urlIndex)
                check(call.registerCount == 4)
                // Wrap the factory call itself: its URL may share the move-result register.
                // No original register is extended, shifted or reused across that write.
                replaceInstruction(
                    urlIndex,
                    "invoke-static { v${call.registerC}, v${call.registerD}, v${call.registerE}, v${call.registerF} }, $helperMethod"
                )
            }
        }
    }
}
