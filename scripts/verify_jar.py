#!/usr/bin/env python3
"""Static verification of the built efnperfectroll jar against the exact Epic Fight jar.

Checks (fails with exit code 1 on any error):
  * jar layout: META-INF/mods.toml, pack.mcmeta, expected classes, @Mod / @EventBusSubscriber present
  * no Mixin usage at all (no mixin json, no org/spongepowered references, no Mixin annotations)
  * every field/method reference from the addon's classes to yesman/epicfight/** classes exists in the
    Epic Fight jar WITH THE SAME JVM DESCRIPTOR (searching superclasses and interfaces)
  * mods.toml declares the expected mod id / dependencies
This does not run Minecraft. It is a static check only.
"""
import argparse
import hashlib
import struct
import sys
import zipfile

KNOWN_EF_SHA256 = "69566cf70ae2d91d3f2564c608f014c87e290cef6215c2a27719851165485f73"  # epic-fight 20.14.17 (user's jar)
errors, notes = [], []


def err(msg):
    errors.append(msg)
    print("ERROR:", msg)


def note(msg):
    notes.append(msg)
    print("OK   :", msg)


class ClassInfo:
    def __init__(self, data):
        self.cp = [None]
        r = Reader(data)
        if r.u4() != 0xCAFEBABE:
            raise ValueError("bad magic")
        r.u2(); self.major = r.u2()
        n = r.u2()
        i = 1
        while i < n:
            t = r.u1()
            if t == 1:
                ln = r.u2(); self.cp.append((1, r.raw(ln).decode("utf-8", "replace")))
            elif t in (3, 4):
                self.cp.append((t, r.u4()))
            elif t in (5, 6):
                self.cp.append((t, r.raw(8))); self.cp.append(None); i += 1
            elif t in (7, 8, 16, 19, 20):
                self.cp.append((t, r.u2()))
            elif t in (9, 10, 11, 12, 17, 18):
                self.cp.append((t, r.u2(), r.u2()))
            elif t == 15:
                self.cp.append((t, r.u1(), r.u2()))
            else:
                raise ValueError("bad cp tag %d" % t)
            i += 1
        r.u2()
        self.name = self.cls(r.u2())
        sup = r.u2()
        self.super = self.cls(sup) if sup else None
        self.ifaces = [self.cls(r.u2()) for _ in range(r.u2())]
        self.fields, self.methods = set(), set()
        self.annotations = set()
        for target in (self.fields, self.methods):
            for _ in range(r.u2()):
                r.u2(); nm = self.utf(r.u2()); ds = self.utf(r.u2())
                target.add((nm, ds))
                for _ in range(r.u2()):
                    an = self.utf(r.u2()); ln = r.u4(); r.raw(ln)
        for _ in range(r.u2()):
            an = self.utf(r.u2()); ln = r.u4(); body = r.raw(ln)
            if an == "RuntimeVisibleAnnotations":
                pass
        # collect annotation types more robustly: any utf8 that looks like an annotation descriptor
        self.utf8s = [c[1] for c in self.cp if c and c[0] == 1]

    def utf(self, i):
        return self.cp[i][1]

    def cls(self, i):
        return self.utf(self.cp[i][1])

    def member_refs(self):
        out = []
        for c in self.cp:
            if c and c[0] in (9, 10, 11):
                owner = self.cls(c[1])
                nt = self.cp[c[2]]
                out.append((c[0], owner, self.utf(nt[1]), self.utf(nt[2])))
        return out


class Reader:
    def __init__(self, b):
        self.b, self.p = b, 0

    def raw(self, n):
        v = self.b[self.p:self.p + n]; self.p += n; return v

    def u1(self): return self.raw(1)[0]
    def u2(self): return struct.unpack(">H", self.raw(2))[0]
    def u4(self): return struct.unpack(">I", self.raw(4))[0]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", required=True)
    ap.add_argument("--epicfight", required=True)
    ap.add_argument("--mod-id", default="efnperfectroll")
    a = ap.parse_args()

    sha = hashlib.sha256(open(a.epicfight, "rb").read()).hexdigest()
    if sha == KNOWN_EF_SHA256:
        note("Epic Fight jar is byte-identical to the 20.14.17 jar provided by the user")
    else:
        print("WARN : Epic Fight jar sha256 differs from the user's file (%s); checking against it anyway" % sha)

    ef = zipfile.ZipFile(a.epicfight)
    ef_toml = ef.read("META-INF/mods.toml").decode("utf-8", "replace")
    if 'version="20.14.17"' in ef_toml:
        note("Epic Fight mods.toml version = 20.14.17")
    else:
        err("Epic Fight jar is not version 20.14.17")
    ef_names = {n[:-6]: n for n in ef.namelist() if n.endswith(".class")}
    ef_cache = {}

    def ef_class(name):
        if name not in ef_names:
            return None
        if name not in ef_cache:
            ef_cache[name] = ClassInfo(ef.read(ef_names[name]))
        return ef_cache[name]

    def resolve(owner, name, desc, is_method, seen=None):
        """True=found, False=missing, None=left the Epic Fight jar (cannot judge)."""
        ci = ef_class(owner)
        if ci is None:
            return None
        if (name, desc) in (ci.methods if is_method else ci.fields):
            return True
        result = False
        for parent in ([ci.super] if ci.super else []) + ci.ifaces:
            r = resolve(parent, name, desc, is_method)
            if r is True:
                return True
            if r is None:
                result = None
        return result

    jar = zipfile.ZipFile(a.jar)
    names = jar.namelist()
    for need in ("META-INF/mods.toml", "pack.mcmeta"):
        (note if need in names else err)(("present: " if need in names else "MISSING: ") + need)
    classes = [n for n in names if n.endswith(".class")]
    for need in ("com/yansel/efnperfectroll/EfnPerfectRoll.class", "com/yansel/efnperfectroll/PerfectRollHandler.class"):
        (note if need in names else err)(("present: " if need in names else "MISSING: ") + need)

    # --- no Mixin anywhere
    bad = [n for n in names if "mixin" in n.lower()]
    (err("unexpected mixin file(s): %s" % bad) if bad else note("no mixin files in jar"))

    toml = jar.read("META-INF/mods.toml").decode("utf-8", "replace")
    for frag in ('modId="%s"' % a.mod_id, 'modId="epicfight"', 'modId="efn"', "${" ):
        if frag == "${":
            (err("unexpanded ${...} placeholder left in mods.toml") if frag in toml else note("mods.toml placeholders expanded"))
        else:
            (note if frag in toml else err)(("mods.toml has " if frag in toml else "mods.toml lacks ") + frag)

    total = checked = 0
    for cn in classes:
        ci = ClassInfo(jar.read(cn))
        if ci.major > 61:
            err("%s compiled for Java > 17 (class major %d)" % (cn, ci.major))
        if any("org/spongepowered" in u for u in ci.utf8s):
            err("%s references org/spongepowered (Mixin)" % cn)
        for kind, owner, name, desc in ci.member_refs():
            if not owner.startswith("yesman/epicfight/"):
                continue
            total += 1
            res = resolve(owner, name, desc, is_method=(kind != 9))
            if res is True:
                checked += 1
            elif res is False:
                err("%s -> %s.%s%s NOT FOUND in Epic Fight jar" % (cn, owner, name, desc))
            else:
                # Our addon only calls members that Epic Fight itself defines, so this is a hard error.
                err("%s -> %s.%s%s not found in the Epic Fight class hierarchy" % (cn, owner, name, desc))
        if cn.endswith("EfnPerfectRoll.class"):
            if "Lnet/minecraftforge/fml/common/Mod;" in ci.utf8s: note("@Mod annotation present in compiled class")
            else: err("@Mod annotation missing in EfnPerfectRoll")
        if cn.endswith("PerfectRollHandler.class"):
            if "Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber;" in ci.utf8s: note("@Mod.EventBusSubscriber present in compiled class")
            else: err("@EventBusSubscriber missing in PerfectRollHandler")
            if "com.hm.efn.skill.dodge.EFNDodgeSkill_Roll" in ci.utf8s: note("Nightfall roll class name constant present")
            else: err("Nightfall roll class name constant missing")
    if total == 0:
        err("no references to Epic Fight found - something is wrong")
    note("Epic Fight member references verified (name + JVM descriptor): %d/%d" % (checked, total))

    print()
    if errors:
        print("VERIFICATION FAILED: %d error(s)" % len(errors))
        sys.exit(1)
    print("VERIFICATION PASSED (static checks only; the mod has NOT been run in Minecraft)")


if __name__ == "__main__":
    main()
