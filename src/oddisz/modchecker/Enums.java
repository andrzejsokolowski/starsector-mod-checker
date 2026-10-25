package oddisz.modchecker;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The names the game accepts for fixed-choice fields, read from Starsector 0.98a's own classes. The
 * game looks these up with Enum.valueOf, so spelling and upper case must match exactly.
 */
final class Enums {
    private Enums() {
    }

    static Set<String> of(String... names) {
        return new LinkedHashSet<>(List.of(names));
    }

    static final Set<String> WEAPON_TYPE = of("BALLISTIC", "ENERGY", "MISSILE", "LAUNCH_BAY", "UNIVERSAL", "HYBRID",
            "SYNERGY", "COMPOSITE", "BUILT_IN", "DECORATIVE", "SYSTEM", "STATION_MODULE");
    static final Set<String> WEAPON_SIZE = of("SMALL", "MEDIUM", "LARGE");
    static final Set<String> SLOT_MOUNT = of("TURRET", "HARDPOINT", "HIDDEN");
    static final Set<String> COLLISION_CLASS = of("NONE", "RAY", "RAY_FIGHTER", "FIGHTER", "SHIP", "PROJECTILE_NO_FF",
            "PROJECTILE_FF", "MISSILE_NO_FF", "MISSILE_FF", "HITS_SHIPS_AND_ASTEROIDS", "HITS_SHIPS_ONLY_FF",
            "HITS_SHIPS_ONLY_NO_FF", "PROJECTILE_FIGHTER", "ASTEROID", "PLANET", "GAS_CLOUD", "STAR");
    static final Set<String> SPAWN_TYPE = of("BEAM", "BALLISTIC_AS_BEAM", "BALLISTIC", "PLASMA", "MISSILE", "OTHER");
    static final Set<String> BARREL_MODE = of("ALTERNATING", "ALTERNATING_BURST", "DUAL", "LINKED");
    static final Set<String> ANIMATION_TYPE = of("NONE", "GLOW", "MUZZLE_FLASH", "GLOW_AND_FLASH", "SMOKE");
    static final Set<String> RENDER_HINT = of("RENDER_BARREL_BELOW", "RENDER_LOADED_MISSILES",
            "RENDER_LOADED_MISSILES_UNLESS_HIDDEN", "RENDER_ADDITIVE", "SUSPEND_RECOIL", "NEVER_RENDER_IN_CAMPAIGN");
    static final Set<String> BEAM_TEXTURE = of("SMOOTH", "MEDIUM", "ROUGH", "CHUNKY", "ROUGH2", "WEAVE", "LASER");
    static final Set<String> PROJ_BEHAVIOR = of("PROXIMITY_FUSE", "MIRV", "CUSTOM");
    static final Set<String> MISSILE_TYPE = of("FLARE", "FLARE_SEEKER", "MOTE", "FLARE_JAMMER", "ROCKET", "MISSILE",
            "MISSILE_TWO_STAGE_SECOND_UNGUIDED", "HEATSEEKER", "CIRCLE_TARGET", "MIRV", "TORPEDO", "BOMB", "BOMB_WITH_SLOW",
            "BOMB_WITH_ACCEL", "PHASE_CHARGE", "PHASE_MINE", "NO_AI");
    static final Set<String> ENGINE_STYLE = of("LOW_TECH", "MIDLINE", "HIGH_TECH", "COBRA_BOMBER", "ATTACK_SWARM", "THREAT",
            "ONSLAUGHT_MKI", "MISSILE_LOW_TECH", "MISSILE_MIDLINE", "MISSILE_HIGH_TECH", "LOW_TECH_FIGHTER", "TORPEDO",
            "TORPEDO_ATROPOS", "ROCKET_LOW_TECH", "OMEGA", "DWELLER", "CUSTOM");
    static final Set<String> HULL_SIZE = of("DEFAULT", "FIGHTER", "FRIGATE", "DESTROYER", "CRUISER", "CAPITAL_SHIP");
    static final Set<String> SHIELD_TYPE = of("NONE", "FRONT", "OMNI", "PHASE");
    static final Set<String> SHIP_HINT = of("FREIGHTER", "TANKER", "LINER", "TRANSPORT", "CIVILIAN", "CARRIER", "COMBAT",
            "NO_AUTO_ESCORT", "UNBOARDABLE", "STATION", "SHIP_WITH_MODULES", "MODULE", "HIDE_IN_CODEX", "UNDER_PARENT",
            "INDEPENDENT_ROTATION", "ALWAYS_PANIC", "WEAPONS_FRONT_TO_BACK", "WEAPONS_BACK_TO_FRONT",
            "DO_NOT_SHOW_MODULES_IN_FLEET_LIST", "RENDER_ENGINES_BELOW_HULL", "NEVER_DODGE_MISSILES",
            "MISSILE_HARDPOINTS_ROTATE", "NO_NEURAL_LINK", "PHASE", "PLAY_FIGHTER_OVERLOAD_SOUNDS");
    static final Set<String> WEAPON_GROUP = of("LINKED", "ALTERNATING");
    static final Set<String> DAMAGE_TYPE = of("KINETIC", "HIGH_EXPLOSIVE", "FRAGMENTATION", "ENERGY", "OTHER");
    static final Set<String> AI_HINT = of("PD", "PD_ONLY", "PD_ALSO", "USE_VS_FRIGATES", "STRIKE", "DANGEROUS", "BOMB",
            "GUIDED_POOR", "DO_NOT_AIM", "ANTI_FTR", "HEATSEEKER", "SYSTEM", "SHOW_IN_CODEX", "AUTOZOOM", "DO_NOT_CONSERVE",
            "CONSERVE_1", "CONSERVE_2", "CONSERVE_3", "CONSERVE_4", "CONSERVE_5", "CONSERVE_ALL", "CONSERVE_FOR_ANTI_ARMOR",
            "FIRE_WHEN_INEFFICIENT", "EXTRA_RANGE_ON_FIGHTER", "IGNORES_FLARES", "GROUP_LINKED", "GROUP_ALTERNATING",
            "MISSILE_SPREAD", "DIRECT_AIM", "NO_TURN_RATE_BOOST_WHEN_IDLE", "RESET_BARREL_INDEX_ON_BURST",
            "USE_LESS_VS_SHIELDS", "RANGE_FROM_TARGETING_OVAL", "RANGE_FROM_SHIP_RADIUS", "IMPORTANT", "NO_MANUAL_FIRE");
    static final Set<String> WING_ROLE = of("BOMBER", "FIGHTER", "INTERCEPTOR", "ASSAULT", "SUPPORT");
    static final Set<String> FORMATION = of("V", "CLAW", "DIAMOND", "BOX");
    static final Set<String> DESCRIPTION_TYPE = of("SHIP", "WEAPON", "SHIP_SYSTEM", "RESOURCE", "ACTION_TOOLTIP", "PLANET",
            "ASTEROID", "FACTION", "TERRAIN", "GALLERY", "CUSTOM");
}
