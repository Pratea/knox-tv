-- KNOX TV - compatibilidade com packs de modelos 3D do Viewpoint (ex:
-- "6258 3D models for Viewpoint", Voxel Studio).
--
-- Com modelo 3D, o Viewpoint nao desenha a camada de tela da TV e o video
-- some em 1a pessoa. Aqui o registro de packs e interceptado: cada manifesto
-- e registrado SEM os modelos de TV (copia gerada pelo Java, ver
-- ModelPackPatch.java). As TVs ficam com o visual padrao do Viewpoint, onde
-- o video aparece; todo o resto continua em 3D. Sem packs, nao faz nada.
--
-- Fica em shared/ pra carregar antes dos arquivos client/ de outros mods,
-- que costumam registrar seus packs assim que carregam.

if isServer() then return end

local function install()
    if ZTV_VIEWPOINT_PATCHED then return true end
    if not (ZTV_filterModelPack and Viewpoint and Viewpoint.ModelPacks and Viewpoint.ModelPacks.register) then
        return false
    end
    local original = Viewpoint.ModelPacks.register
    Viewpoint.ModelPacks.register = function(modId, manifest, ...)
        local filtered = ZTV_filterModelPack(modId, manifest)
        return original(modId, filtered or manifest, ...)
    end
    ZTV_VIEWPOINT_PATCHED = true
    print("[KNOX TV] patch de packs do Viewpoint ativo (TVs sem modelo 3D)")
    return true
end

-- O Viewpoint pode ainda nao ter criado a API quando este arquivo carrega:
-- tenta de novo no client e no boot (antes do registro tardio dos packs).
if not install() then
    Events.OnGameBoot.Add(install)
end
ZTVViewpointPatchInstall = install
