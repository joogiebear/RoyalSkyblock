# Updating when Auxilor releases

RoyalSkyblock (and the `ecominions` extension) compile against eco and libreforge. This is what
to do when a new Auxilor release lands on the server, and — just as important — when nothing
needs doing.

## The one-minute version

| Server change | Do you rebuild? |
| --- | --- |
| eco updated, libreforge version unchanged | **No.** eco is a stable Java API; older builds run on newer eco. |
| Any Auxilor plugin updated and `plugins/libreforge/versions/` gained a newer jar | **Yes** — follow the steps below. |
| Only your own plugins changed | No. |

The version that matters is **libreforge's**, and it is not the one in our `gradle.properties`.
The libreforge loader runs a single shared copy at the *highest* version any installed plugin
asks for. Update EcoItems and it may drag libreforge up for everyone, RoyalSkyblock included.

## Why libreforge breaks and eco doesn't

libreforge is Kotlin. A Kotlin function with default arguments is called through a synthetic
`name$default` bridge whose signature lists every parameter, so when Auxilor adds a parameter
(2026.35.1 added three to `ConfigArgumentsBuilder.require`) every jar compiled against the older
version throws `NoSuchMethodError` at class-init time and the plugin fails to enable. The source
still compiles fine, which is why a rebuild alone never reveals it and why the check in step 4
exists.

Since that incident our own code calls the non-defaulted `require` overload
(`requireStable` in `IslandConditions.kt`, inline in `ConditionMinionCount.kt`), so *that* break
cannot recur. Other libreforge entry points could still move; the steps below catch that.

## Steps

### 1. Find the libreforge version the server actually loads

```bash
ls S:/mcctl/instances/Skyblock/plugins/libreforge/versions/
```

The newest jar there is the ABI you must match. The eco version is on the eco jar in `plugins/`.

### 2. Bump both repos to those versions

`RoyalSkyblock/gradle.properties` and `royalskyblock-extensions/gradle.properties`:

```properties
eco-version=2026.35
libreforge-version=2026.35.1
```

### 3. Build RoyalSkyblock, then the extensions

```bash
cd S:/Claude/royal-plugins/RoyalSkyblock && ./gradlew.bat build --no-daemon
```

```bash
cd S:/Claude/royal-plugins/royalskyblock-extensions && powershell -NoProfile -File tools/install-deps.ps1 && ./gradlew.bat build --no-daemon
```

(`install-deps.ps1` publishes the freshly built RoyalSkyblock jar to the local Maven repository
the extensions compile against.)

### 4. Binary check — the step a green build does not cover

Confirm no class still links a default-argument bridge into libreforge. Expect `0` for both:

```bash
cd S:/Claude/royal-plugins/RoyalSkyblock && for c in $(unzip -l build/libs/RoyalSkyblock.jar | grep -o 'com/mystipixel/royalskyblock/libreforge/[^ ]*\.class' | sed 's#/#.#g; s#\.class$##'); do javap -v -cp build/libs/RoyalSkyblock.jar "$c" 2>/dev/null | grep -c 'com/willfp/libreforge/.*\$default'; done | sort -u
```

```bash
cd S:/Claude/royal-plugins/royalskyblock-extensions && javap -v -cp ecominions/build/libs/EcoMinions.jar com.mystipixel.royalskyblock.ext.ecominions.ConditionMinionCount | grep -c 'com/willfp/libreforge/.*\$default'
```

A non-zero count names a call that would break the next time Auxilor adds a parameter there.
Fix it the same way `requireStable` does: find an overload of the same function with no
defaults (`javap -p -cp <libreforge jar> <class>` lists them) and call that instead.

### 5. Deploy and smoke-boot

```bash
cp S:/Claude/royal-plugins/RoyalSkyblock/build/libs/RoyalSkyblock.jar S:/mcctl/instances/Skyblock/plugins/RoyalSkyblock.jar
```

Extension jars go to `plugins/RoyalSkyblock/extensions/` (only the ones the server uses; the
Java-only ones — EcoMobs, EcoSkills, MythicMobs — never need a rebuild for a libreforge bump).

Boot the test instance, then:

```bash
grep -nE 'Error occurred while enabling Royal|NoSuchMethodError|NoClassDefFoundError' S:/mcctl/instances/Skyblock/logs/latest.log
```

Empty output is the pass. Also expect `[RoyalSkyblock] ... connected to SQLITE storage` followed by
no `Disabling RoyalSkyblock` line.

### 6. Commit and push, both repos

```bash
git commit -am "build: compile against eco 2026.35 and libreforge 2026.35.1"
```

Use `fix:` instead if the server was already failing to boot when you did this — name the commit
for why it exists.

## If it still breaks

The stack trace names the missing method. Compare the two libreforge jars — the version you built
against sits in `~/.gradle/caches/modules-2/files-2.1/com.willfp/libreforge/<version>/` — with:

```bash
javap -p -cp <jar> com.willfp.libreforge.<Class> | grep <method>
```

Whatever changed, the fix is the same shape as `requireStable`: bind to a signature without
defaults, or adapt to the new one. Then repeat from step 3.
