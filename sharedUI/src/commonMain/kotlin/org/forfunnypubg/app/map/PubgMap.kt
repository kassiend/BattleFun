package org.forfunnypubg.app.map

import battlefun.sharedui.generated.resources.Res
import battlefun.sharedui.generated.resources.deston_secrets
import battlefun.sharedui.generated.resources.erangel_secrets
import battlefun.sharedui.generated.resources.karakin_secrets
import battlefun.sharedui.generated.resources.miramar_secrets
import battlefun.sharedui.generated.resources.paramo_secrets
import battlefun.sharedui.generated.resources.rondo_secrets
import battlefun.sharedui.generated.resources.sanhok_secrets
import battlefun.sharedui.generated.resources.taego_secrets
import battlefun.sharedui.generated.resources.vikendi_secrets
import org.jetbrains.compose.resources.DrawableResource

enum class PubgMap(
    val displayName: String,
    val sizeKm: Float,
    val resource: DrawableResource,
) {
    ERANGEL("Erangel", 8f, Res.drawable.erangel_secrets),
    MIRAMAR("Miramar", 8f, Res.drawable.miramar_secrets),
    TAEGO("Taego", 8f, Res.drawable.taego_secrets),
    DESTON("Deston", 8f, Res.drawable.deston_secrets),
    RONDO("Rondo", 8f, Res.drawable.rondo_secrets),
    VIKENDI("Vikendi", 6f, Res.drawable.vikendi_secrets),
    SANHOK("Sanhok", 4f, Res.drawable.sanhok_secrets),
    PARAMO("Paramo", 3f, Res.drawable.paramo_secrets),
    KARAKIN("Karakin", 2f, Res.drawable.karakin_secrets),
}
