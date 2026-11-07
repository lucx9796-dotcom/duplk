package br.duplas;

import java.util.*;

/** Catalogo de estruturas (Overworld, Nether e End). */
public final class Structures {

    public record Def(String id, String display, List<String> keys) {}

    public static final Map<String, Def> ALL = new LinkedHashMap<>();
    private static final Map<String, Def> BY_KEY = new HashMap<>();

    private static void add(String id, String display, String... keys) {
        Def d = new Def(id, display, List.of(keys));
        ALL.put(id, d);
        for (String k : keys) BY_KEY.put(k, d);
    }

    static {
        add("casa_da_bruxa", "Casa da Bruxa", "swamp_hut");
        add("posto_de_saqueadores", "Posto de Saqueadores", "pillager_outpost");
        add("mina_abandonada", "Mina Abandonada", "mineshaft", "mineshaft_mesa");
        add("mansao", "Mansao da Floresta", "mansion");
        add("templo_da_selva", "Templo da Selva", "jungle_pyramid");
        add("templo_do_deserto", "Templo do Deserto", "desert_pyramid");
        add("iglu", "Iglu", "igloo");
        add("naufragio", "Naufragio", "shipwreck", "shipwreck_beached");
        add("fortaleza", "Fortaleza (Stronghold)", "stronghold");
        add("monumento_oceanico", "Monumento Oceanico", "monument");
        add("ruinas_oceanicas", "Ruinas Oceanicas", "ocean_ruin_cold", "ocean_ruin_warm");
        add("tesouro_enterrado", "Tesouro Enterrado", "buried_treasure");
        add("portal_em_ruinas", "Portal em Ruinas", "ruined_portal", "ruined_portal_desert",
                "ruined_portal_jungle", "ruined_portal_swamp", "ruined_portal_mountain", "ruined_portal_ocean",
                "ruined_portal_nether");
        add("vila", "Vila", "village_plains", "village_desert", "village_savanna", "village_snowy", "village_taiga");
        add("ruinas_de_trilha", "Ruinas de Trilha", "trail_ruins");
        add("cidade_antiga", "Cidade Antiga", "ancient_city");
        add("camara_de_provacao", "Camara de Provacao", "trial_chambers");
        // Nether
        add("bastion", "Bastion Remnant", "bastion_remnant");
        add("fortaleza_do_nether", "Fortaleza do Nether", "fortress");
        add("fossil_do_nether", "Fóssil do Nether", "nether_fossil");
        // End
        add("end_city", "End City", "end_city");
    }

    public static Def get(String id) {
        return id == null ? null : ALL.get(id.toLowerCase());
    }

    public static Def byKey(String key) {
        return BY_KEY.get(key);
    }

    private Structures() {}
}
