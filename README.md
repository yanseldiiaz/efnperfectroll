# EFN Perfect Roll 2.0.0

Addon para **Forge 1.20.1 (47.4.4)** + **Epic Fight 20.14.17** + **EpicFight-Nightfall 3.4.0**.

Un **Perfect Roll** (un roll de Nightfall que esquiva con éxito un ataque) **no cuesta stamina**.
Todo lo demás queda igual: un roll sin ataque que esquivar, o un roll que recibe el golpe, cuesta su stamina normal.
Parry, bloqueo y el resto de habilidades no se tocan.

> ⚠️ **Estado honesto:** este mod se verificó de forma *estática* (firmas y descriptores contra el JAR real de Epic Fight 20.14.17).
> **No se ha ejecutado en Minecraft** (el entorno donde se escribió no puede arrancar el juego). Pruébalo primero en una copia del modpack.

## Cómo funciona (sin Mixin)
* Nightfall no tiene un evento propio de “Perfect Roll”. Epic Fight sí dispara `DodgeSuccessEvent` **solo** cuando un ataque golpea el indicador de esquiva que crea el roll (no en cualquier roll).
* El coste del roll se cobra al lanzarlo (antes de saber si habrá ataque), así que no se puede anular de antemano. El addon:
  1. En `SKILL_CONSUME_EVENT` recuerda el último roll de Nightfall (`EFNDodgeSkill_Roll`) y la stamina que había justo antes.
  2. En `DODGE_SUCCESS_EVENT`, si ese roll es reciente (≤ 12 ticks) y realmente cobró stamina, devuelve **exactamente esa cantidad, una sola vez**, en el servidor. La stamina de Epic Fight se sincroniza sola con el cliente.
* No usa Mixin, ni modifica los JAR de Epic Fight o Nightfall.

## Cómo obtener el .jar (sin compilar nada tú)
1. Crea una cuenta en GitHub y un repositorio nuevo (por ejemplo `efnperfectroll`).
2. En el repositorio: **Add file → Upload files** y arrastra **el contenido** de esta carpeta (incluida la carpeta oculta `.github`). Confirma con **Commit changes**.
   Comprueba que existe `.github/workflows/build.yml`.
3. Ve a la pestaña **Actions**. Verás el workflow “Build efnperfectroll” ejecutándose (tarda unos minutos la primera vez).
4. Cuando termine con ✅, abre la ejecución y, abajo en **Artifacts**, descarga **efnperfectroll-jar** (es un .zip; dentro está `efnperfectroll-2.0.0.jar`).
   Si el workflow falla ❌, copia el texto del error del paso que falló y envíamelo.
* El workflow solo sube el .jar si pasa `scripts/verify_jar.py` (mods.toml, anotaciones, sin Mixin y todas las firmas contra Epic Fight 20.14.17).
* Opcional: si creas un tag `v2.0.0`, el .jar también se publica en **Releases**.

## Instalación
**Primero en una copia local** (CurseForge → tu instancia → ⋯ → *Open Folder* → carpeta `mods`):
1. Haz una copia de seguridad de la instancia.
2. Copia `efnperfectroll-2.0.0.jar` a `mods/`. Requiere Epic Fight 20.14.17 y EpicFight-Nightfall 3.4.0.
3. Arranca el juego. En el log (`logs/latest.log`) busca `efnperfectroll`.

**Después, en el servidor dedicado** (solo tras validar): copia el mismo .jar a la carpeta `mods/` del servidor y reinícialo. Los clientes **no** necesitan el mod (`displayTest=IGNORE_SERVER_VERSION`), aunque no pasa nada si lo tienen.

## Pruebas manuales recomendadas
Con stamina visible (o `/data`/F3 si lo tienes): 1) roll sin ataque → gasta stamina; 2) roll que recibe el golpe → gasta stamina; 3) roll que esquiva en la ventana → en el log aparece `Perfect Roll: refunded X stamina` y la stamina vuelve a su valor previo; 4) varios perfect rolls seguidos; 5) bloqueo y parry igual que antes.

## Compilar localmente (opcional)
`gradlew.bat build` con Java 17. El resultado queda en `build/libs/`.
