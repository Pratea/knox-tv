# Pedido aos autores do Viewpoint

Contexto: em 1ª pessoa o Viewpoint lê a textura da camada de tela da TV pelo
tamanho em pixels declarado (`width`/`height`), não pelas coordenadas de
textura (UV). Por isso a tela do KNOX TV só funciona em 1x em 1ª pessoa
(testado: com `screen.scale=4` a tela fica preta no Viewpoint e funciona na
câmera isométrica). Mensagem para postar nos comentários da Workshop do
Viewpoint ou no canal deles:

---

Hi! I'm making **KNOX TV**, a ZombieBuddy mod that plays live video on in-game TVs. It replaces the TV's `overlaySprite` (screen layer) with a sprite whose `Texture` is updated every frame, and it already works in Viewpoint at 1x (thanks!).

To make the screen sharper I tried a higher-res texture: the `TextureID` is 4x the size, while the `Texture` keeps the vanilla logical size (`width`/`height`/`widthOrig`/`heightOrig`/`offsetX`/`offsetY`), and its UVs (`xStart..xEnd`, `yStart..yEnd`) cover the whole image. The vanilla iso renderer draws this correctly, because it samples by UV. Viewpoint seems to read the texture by its logical pixel size instead, so it only gets the top-left `width x height` pixels and the screen comes out empty.

Would you consider any of these?

- **(a)** Sampling sprite/overlay textures by their UVs (`getXStart/getXEnd/getYStart/getYEnd`) instead of the logical pixel size. This is the simplest one and would fix it on its own.
- **(b)** A small API so a mod can register a dynamic, higher-resolution texture for a sprite.
- **(c)** A Lua/Java API to project a world position to screen coordinates (e.g. `Viewpoint.worldToScreen(x, y, z)`) plus a way to know whether first person is active, so mods can draw overlays aligned with world objects.

Any one of them would let KNOX TV show sharp video in first person. Happy to test any build. Thanks!
