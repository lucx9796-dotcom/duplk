package br.duplas;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.stream.Collectors;

public class DuplaCommand implements CommandExecutor, TabCompleter {

    private static final String NOME_VALIDO = "[\\p{L}\\p{N}_ -]{1,24}";

    private final DuplasPlugin pl;

    public DuplaCommand(DuplasPlugin pl) {
        this.pl = pl;
    }

    private void msg(CommandSender s, String m) {
        s.sendMessage(m);
    }

    private boolean admin(CommandSender s) {
        if (s.hasPermission("duplas.admin")) return true;
        msg(s, "§cSem permissão.");
        return false;
    }

    /** Junta a[from..to) com espaco (nomes de dupla podem ter varias palavras). */
    private static String join(String[] a, int from, int to) {
        return String.join(" ", Arrays.copyOfRange(a, from, to));
    }

    /** Acha o jogador: online (exato) -> cache do servidor -> quem ja jogou aqui. */
    private OfflinePlayer resolve(String name) {
        Player on = Bukkit.getPlayerExact(name);
        if (on != null) return on;
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null) return cached;
        OfflinePlayer op = Bukkit.getOfflinePlayer(name);
        return op.hasPlayedBefore() ? op : null;
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        switch (c.getName().toLowerCase()) {
            case "renegar":
                return renegar(s, a, true);
            case "desrenegar":
                return renegar(s, a, false);
            case "renegado":
                return renegado(s, a);
            default:
                return dupla(s, a);
        }
    }

    // ================= /dupla =================

    private boolean dupla(CommandSender s, String[] a) {
        if (a.length == 0) {
            help(s);
            return true;
        }
        switch (a[0].toLowerCase()) {
            case "criar" -> criar(s, a);
            case "remover" -> remover(s, a);
            case "estrutura" -> estrutura(s, a);
            case "lista" -> lista(s);
            case "chave" -> chave(s, a);
            case "alertas" -> alertas(s, a);
            default -> help(s);
        }
        return true;
    }

    private void help(CommandSender s) {
        msg(s, "§e/dupla criar <nome> <jogador1> <jogador2> §7(o nome pode ter espaços)");
        msg(s, "§e/dupla remover <nome>");
        msg(s, "§e/dupla estrutura <nome> [estrutura] §7(sem estrutura = lista as disponíveis)");
        msg(s, "§e/dupla lista");
        msg(s, "§e/dupla chave definir <nome> §7(item na mão vira a chave dessa dupla)");
        msg(s, "§e/dupla chave dar <nome>");
        msg(s, "§e/dupla alertas [nome] [limpar]");
        msg(s, "§e/renegar <jogador> §7| §e/desrenegar <jogador> §7| §e/renegado lista");
    }

    private void criar(CommandSender s, String[] a) {
        if (!admin(s)) return;
        // /dupla criar <nome...> <jogador1> <jogador2>
        if (a.length < 4) {
            msg(s, "§cUso: /dupla criar <nome> <jogador1> <jogador2>");
            return;
        }
        String name = join(a, 1, a.length - 2).trim();
        String n1 = a[a.length - 2], n2 = a[a.length - 1];

        if (!name.matches(NOME_VALIDO)) {
            msg(s, "§cNome inválido: use de 1 a 24 caracteres (letras, números, espaço, _ ou -).");
            return;
        }
        if (pl.duplas.containsKey(name.toLowerCase())) {
            msg(s, "§cJá existe uma dupla chamada §f" + name + "§c.");
            return;
        }
        OfflinePlayer p1 = resolve(n1);
        if (p1 == null) {
            msg(s, "§cJogador §f" + n1 + " §cnão encontrado (precisa estar online ou já ter entrado no servidor).");
            return;
        }
        OfflinePlayer p2 = resolve(n2);
        if (p2 == null) {
            msg(s, "§cJogador §f" + n2 + " §cnão encontrado (precisa estar online ou já ter entrado no servidor).");
            return;
        }
        if (p1.getUniqueId().equals(p2.getUniqueId())) {
            msg(s, "§cEscolha dois jogadores diferentes.");
            return;
        }
        for (OfflinePlayer p : List.of(p1, p2)) {
            Dupla ex = pl.duplaOf(p.getUniqueId());
            if (ex != null) {
                msg(s, "§c" + pl.nameOf(p.getUniqueId()) + " já está na dupla " + ex.name + ".");
                return;
            }
        }
        Dupla d = new Dupla(name);
        d.members.add(p1.getUniqueId());
        d.members.add(p2.getUniqueId());
        pl.duplas.put(name.toLowerCase(), d);
        pl.markDirty();
        pl.saveData();
        msg(s, "§aDupla §f" + name + " §acriada: §f" + pl.nameOf(p1.getUniqueId())
                + " §ae §f" + pl.nameOf(p2.getUniqueId()));
    }

    private void remover(CommandSender s, String[] a) {
        if (!admin(s)) return;
        if (a.length < 2) {
            msg(s, "§cUso: /dupla remover <nome>");
            return;
        }
        Dupla d = pl.duplas.remove(join(a, 1, a.length).toLowerCase());
        if (d == null) {
            msg(s, "§cDupla não encontrada.");
            return;
        }
        pl.markDirty();
        msg(s, "§aDupla §f" + d.name + " §aremovida.");
    }

    private void estrutura(CommandSender s, String[] a) {
        if (!admin(s)) return;
        if (a.length < 2) {
            msg(s, "§cUso: /dupla estrutura <nome> <estrutura>");
            return;
        }
        // Se o ultimo argumento for um id de estrutura valido, o resto e o nome da dupla.
        Structures.Def def = a.length >= 3 ? Structures.get(a[a.length - 1]) : null;
        String nome = def != null ? join(a, 1, a.length - 1) : join(a, 1, a.length);
        Dupla d = pl.duplas.get(nome.toLowerCase());
        if (d == null) {
            msg(s, "§cDupla não encontrada.");
            if (a.length >= 3 && def == null) {
                msg(s, "§7(Se você quis definir uma estrutura, o id §f" + a[a.length - 1] + " §7não existe.)");
                listarEstruturas(s);
            }
            return;
        }
        if (def == null) {
            listarEstruturas(s);
            return;
        }
        Dupla other = pl.ownerOfStructure(def.id());
        if (other != null && other != d) {
            msg(s, "§cA estrutura §f" + def.display() + " §cjá pertence à dupla §f" + other.name + "§c.");
            return;
        }
        d.structure = def.id();
        pl.markDirty();
        msg(s, "§aA dupla §f" + d.name + " §aagora é dona de: §e" + def.display()
                + " §7(todas as estruturas desse tipo, em qualquer dimensão)");
    }

    private void listarEstruturas(CommandSender s) {
        msg(s, "§6Estruturas disponíveis:");
        for (Structures.Def def : Structures.ALL.values()) {
            Dupla o = pl.ownerOfStructure(def.id());
            msg(s, " §e" + def.id() + " §7- " + def.display()
                    + (o != null ? " §c(em uso: " + o.name + ")" : " §a(livre)"));
        }
    }

    private void lista(CommandSender s) {
        if (!admin(s)) return;
        if (pl.duplas.isEmpty()) {
            msg(s, "§7Nenhuma dupla criada.");
            return;
        }
        for (Dupla d : pl.duplas.values()) {
            String mem = d.members.stream().map(u -> (pl.renegados.contains(u) ? "§c" : "§f") + pl.nameOf(u))
                    .collect(Collectors.joining("§7, "));
            Structures.Def def = Structures.get(d.structure);
            msg(s, "§e" + d.name + " §7[" + mem + "§7] estrutura: §f" + (def != null ? def.display() : "nenhuma")
                    + " §7| alertas: §f" + d.alerts.size());
        }
    }

    private void chave(CommandSender s, String[] a) {
        if (!admin(s)) return;
        if (a.length < 3) {
            msg(s, "§cUso: /dupla chave definir <nome> §7(item na mão) §c| /dupla chave dar <nome>");
            return;
        }
        boolean definir = a[1].equalsIgnoreCase("definir");
        boolean dar = a[1].equalsIgnoreCase("dar");
        if (!definir && !dar) {
            msg(s, "§cUso: /dupla chave <definir|dar> <nome>");
            return;
        }
        Dupla d = pl.duplas.get(join(a, 2, a.length).toLowerCase());
        if (d == null) {
            msg(s, "§cDupla não encontrada.");
            return;
        }
        if (definir) {
            if (!(s instanceof Player p)) {
                msg(s, "§cApenas jogadores.");
                return;
            }
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (hand.getType().isAir()) {
                msg(s, "§cSegure o item que será a chave.");
                return;
            }
            ItemStack t = hand.clone();
            t.setAmount(1);
            ItemMeta meta = t.getItemMeta();
            if (meta != null) {
                meta.getPersistentDataContainer().remove(pl.keyOwner);
                t.setItemMeta(meta);
            }
            d.keyTemplate = t;
            pl.markDirty();
            msg(s, "§aChave da dupla §f" + d.name + " §adefinida: §f" + t.getType().name());
            msg(s, "§7Use §e/dupla chave dar " + d.name + " §7para entregar aos membros.");
        } else {
            if (d.keyTemplate == null) {
                msg(s, "§cDefina a chave dessa dupla primeiro: /dupla chave definir " + d.name);
                return;
            }
            for (UUID u : d.members) {
                Player p = Bukkit.getPlayer(u);
                if (p == null) {
                    msg(s, "§e" + pl.nameOf(u) + " está offline, não recebeu a chave.");
                    continue;
                }
                if (pl.chaves.possui(p, u)) {
                    d.keyGiven.add(u);
                    msg(s, "§e" + p.getName() + " já está com a própria chave.");
                    continue;
                }
                ItemStack key = d.keyTemplate.clone();
                ItemMeta meta = key.getItemMeta();
                meta.getPersistentDataContainer().set(pl.keyOwner, PersistentDataType.STRING, u.toString());
                key.setItemMeta(meta);
                Map<Integer, ItemStack> left = p.getInventory().addItem(key);
                left.values().forEach(it -> p.getWorld().dropItemNaturally(p.getLocation(), it));
                d.keyGiven.add(u);
                msg(s, "§aChave entregue a §f" + p.getName());
            }
            pl.markDirty();
        }
    }

    private void alertas(CommandSender s, String[] a) {
        boolean limpar = a.length >= 3 && a[a.length - 1].equalsIgnoreCase("limpar");
        int end = limpar ? a.length - 1 : a.length;
        Dupla d;
        if (end >= 2) {
            if (!admin(s)) return;
            d = pl.duplas.get(join(a, 1, end).toLowerCase());
        } else {
            d = (s instanceof Player p) ? pl.duplaOf(p.getUniqueId()) : null;
        }
        if (d == null) {
            msg(s, "§cDupla não encontrada.");
            return;
        }
        if (limpar) {
            if (!admin(s)) return;
            d.alerts.clear();
            pl.markDirty();
            msg(s, "§aAlertas da dupla §f" + d.name + " §alimpos.");
            return;
        }
        if (d.alerts.isEmpty()) {
            msg(s, "§7Nenhum alerta registrado.");
            return;
        }
        int from = Math.max(0, d.alerts.size() - 15);
        for (Alert al : d.alerts.subList(from, d.alerts.size())) al.lines().forEach(l -> msg(s, l));
    }

    // ================= /renegar, /desrenegar, /renegado =================

    private boolean renegar(CommandSender s, String[] a, boolean add) {
        if (!admin(s)) return true;
        String cmd = add ? "renegar" : "desrenegar";
        if (a.length != 1) {
            msg(s, "§cUso: /" + cmd + " <jogador>");
            return true;
        }
        OfflinePlayer op = resolve(a[0]);
        if (op == null) {
            msg(s, "§cJogador §f" + a[0] + " §cnão encontrado.");
            return true;
        }
        if (add == pl.renegados.contains(op.getUniqueId())) {
            msg(s, add ? "§cEsse jogador já é renegado." : "§cEsse jogador não é renegado.");
            return true;
        }
        pl.setRenegado(op.getUniqueId(), add, null);
        return true;
    }

    private boolean renegado(CommandSender s, String[] a) {
        if (!admin(s)) return true;
        if (a.length == 0) {
            msg(s, "§eUso: /renegado <adicionar|remover|lista> [jogador]");
            return true;
        }
        switch (a[0].toLowerCase()) {
            case "lista" -> {
                if (pl.renegados.isEmpty()) msg(s, "§7Nenhum renegado.");
                else msg(s, "§cRenegados: §f" + pl.renegados.stream().map(pl::nameOf).collect(Collectors.joining(", ")));
            }
            case "adicionar", "remover" -> {
                if (a.length != 2) {
                    msg(s, "§cUso: /renegado " + a[0] + " <jogador>");
                    return true;
                }
                return renegar(s, new String[]{a[1]}, a[0].equalsIgnoreCase("adicionar"));
            }
            default -> msg(s, "§eUso: /renegado <adicionar|remover|lista> [jogador]");
        }
        return true;
    }

    // ================= tab complete =================

    private List<String> filter(Collection<String> opts, String prefix) {
        String p = prefix.toLowerCase();
        return opts.stream().filter(o -> o.toLowerCase().startsWith(p)).sorted().collect(Collectors.toList());
    }

    private List<String> online() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        String cn = c.getName().toLowerCase();
        if (!s.hasPermission("duplas.admin")) {
            if (cn.equals("dupla") && a.length == 1) return filter(List.of("alertas"), a[0]);
            return List.of();
        }
        if (cn.equals("renegar")) {
            return a.length == 1 ? filter(online(), a[0]) : List.of();
        }
        if (cn.equals("desrenegar")) {
            return a.length == 1
                    ? filter(pl.renegados.stream().map(pl::nameOf).collect(Collectors.toList()), a[0])
                    : List.of();
        }
        if (cn.equals("renegado")) {
            if (a.length == 1) return filter(List.of("adicionar", "remover", "lista"), a[0]);
            if (a.length == 2 && a[0].equalsIgnoreCase("adicionar")) return filter(online(), a[1]);
            if (a.length == 2 && a[0].equalsIgnoreCase("remover"))
                return filter(pl.renegados.stream().map(pl::nameOf).collect(Collectors.toList()), a[1]);
            return List.of();
        }
        // sugere so nomes de uma palavra (nomes com espaco precisam ser digitados)
        List<String> nomes = pl.duplas.values().stream().map(d -> d.name)
                .filter(n -> !n.contains(" ")).collect(Collectors.toList());
        if (a.length == 1) return filter(List.of("criar", "remover", "estrutura", "lista", "chave", "alertas"), a[0]);
        switch (a[0].toLowerCase()) {
            case "criar":
                if (a.length >= 3) return filter(online(), a[a.length - 1]);
                break;
            case "remover":
                if (a.length == 2) return filter(nomes, a[1]);
                break;
            case "estrutura":
                if (a.length == 2) return filter(nomes, a[1]);
                if (a.length == 3) return filter(Structures.ALL.keySet(), a[2]);
                break;
            case "alertas":
                if (a.length == 2) return filter(nomes, a[1]);
                if (a.length == 3) return filter(List.of("limpar"), a[2]);
                break;
            case "chave":
                if (a.length == 2) return filter(List.of("definir", "dar"), a[1]);
                if (a.length == 3 && (a[1].equalsIgnoreCase("dar") || a[1].equalsIgnoreCase("definir")))
                    return filter(nomes, a[2]);
                break;
        }
        return List.of();
    }
}
