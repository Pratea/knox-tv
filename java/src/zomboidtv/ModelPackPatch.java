package zomboidtv;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import se.krka.kahlua.integration.annotations.LuaMethod;
import zombie.ZomboidFileSystem;
import zombie.gameStates.ChooseGameInfo;

/**
 * Compatibilidade com packs de modelos 3D do Viewpoint (ex: Voxel Studio).
 *
 * Quando um sprite tem modelo 3D num pack, o Viewpoint deixa de desenhar a
 * camada de tela da TV -- o video some em 1a pessoa. A solucao e registrar
 * esses packs SEM os modelos de TV: a TV volta ao visual padrao do Viewpoint
 * (onde o video aparece) e o resto continua em 3D.
 *
 * O Lua (ZTV_ViewpointPatch.lua) intercepta Viewpoint.ModelPacks.register e
 * pede aqui uma copia do manifesto sem as linhas `bind.` de TVs. A copia e
 * gerada no PC do jogador, ao lado do original (o Viewpoint so le manifestos
 * dentro da pasta do mod que registra) -- nenhum arquivo do outro mod e
 * alterado nem redistribuido. Se nao ha TV no manifesto, nada e gerado.
 */
public final class ModelPackPatch {
    private static final String PREFIX = "ztv-notv-";

    private ModelPackPatch() {}

    /**
     * Caminho (relativo, no mesmo formato recebido) de uma copia do manifesto
     * sem os modelos de TV, ou null pra usar o original.
     */
    @LuaMethod(name = "ZTV_filterModelPack", global = true)
    public static String filter(String modId, String manifest) {
        try {
            if (modId == null || manifest == null || manifest.contains(PREFIX)) return null;
            File original = locate(modId, manifest);
            if (original == null) {
                Log.info("patch de packs: manifesto nao encontrado (" + modId + ", " + manifest + ")");
                return null;
            }
            List<String> kept = new ArrayList<>();
            int removed = 0;
            for (String line : Files.readAllLines(original.toPath(), StandardCharsets.UTF_8)) {
                if (isTvBinding(line)) {
                    removed++;
                } else {
                    kept.add(line);
                }
            }
            if (removed == 0) return null;

            File copy = new File(original.getParentFile(), PREFIX + original.getName());
            // So reescreve se mudou (evita mexer no disco a cada abertura do jogo)
            byte[] data = (String.join("\n", kept) + "\n").getBytes(StandardCharsets.UTF_8);
            if (!copy.isFile() || !java.util.Arrays.equals(Files.readAllBytes(copy.toPath()), data)) {
                Files.write(copy.toPath(), data);
            }
            int slash = manifest.lastIndexOf('/');
            String relative = (slash >= 0 ? manifest.substring(0, slash + 1) : "") + copy.getName();
            Log.info("patch de packs: " + modId + " " + manifest + " sem " + removed + " modelo(s) de TV");
            return relative;
        } catch (Exception e) {
            Log.error("patch de packs (" + modId + ", " + manifest + ")", e);
            return null;
        }
    }

    /** Linha `bind.<sprite>=...` de um sprite de TV. */
    private static boolean isTvBinding(String line) {
        String l = line.trim();
        if (!l.startsWith("bind.")) return false;
        int eq = l.indexOf('=');
        String sprite = (eq > 0 ? l.substring(5, eq) : l.substring(5)).toLowerCase();
        return sprite.contains("television");
    }

    /** Acha o manifesto nas pastas do mod (versao, common ou raiz), na ordem em que o B42 resolve arquivos. */
    private static File locate(String modId, String manifest) {
        String dir = ZomboidFileSystem.instance.getModDir(modId);
        if (dir == null) return null;
        List<String> roots = new ArrayList<>();
        ChooseGameInfo.Mod mod = ZomboidFileSystem.instance.getModInfoForDir(dir);
        if (mod != null) {
            if (mod.getVersionDir() != null) roots.add(mod.getVersionDir());
            if (mod.getCommonDir() != null) roots.add(mod.getCommonDir());
        }
        roots.add(dir);
        for (String root : roots) {
            File f = new File(root, manifest);
            if (f.isFile()) return f;
        }
        return null;
    }
}
