package br.duplas;

import org.bukkit.inventory.ItemStack;

import java.util.*;

public class Dupla {
    public final String name;
    public final List<UUID> members = new ArrayList<>();
    public String structure; // id do catalogo (ex: casa_da_bruxa) ou null
    public ItemStack keyTemplate; // item da chave DESTA dupla (null = ainda nao definido)
    public final Set<UUID> keyGiven = new LinkedHashSet<>(); // membros que ja receberam a chave
    public final List<Alert> alerts = new ArrayList<>();

    public Dupla(String name) {
        this.name = name;
    }
}
