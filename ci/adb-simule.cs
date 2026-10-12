// Faux « adb.exe » pour vérifier TRANSFERER-LES-DONNEES.bat sur Windows sans téléphone.
// L'état du « téléphone » est dans %STUB_DIR%\etat.txt ; chaque commande est notée dans commandes.log.
// C# 5 (compilateur du .NET Framework livré avec Windows).
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;

class AdbSimule
{
    static string D = Environment.GetEnvironmentVariable("STUB_DIR");
    static Dictionary<string, string> E = new Dictionary<string, string>();
    const string TMP = "/data/local/tmp/ecole-sauvegarde.tar";

    static void Charger()
    {
        foreach (var l in File.ReadAllLines(Path.Combine(D, "etat.txt")))
        {
            int i = l.IndexOf('=');
            if (i > 0) E[l.Substring(0, i).Trim()] = l.Substring(i + 1).Trim();
        }
    }

    static void Enregistrer()
    {
        File.WriteAllLines(Path.Combine(D, "etat.txt"), E.Select(kv => kv.Key + "=" + kv.Value).ToArray());
    }

    static string V(string cle) { return E.ContainsKey(cle) ? E[cle] : ""; }

    static string Tel(string chemin)
    {
        Directory.CreateDirectory(Path.Combine(D, "tel"));
        return Path.Combine(D, "tel", chemin.Replace('/', '_'));
    }

    static int Main(string[] a)
    {
        Charger();
        File.AppendAllText(Path.Combine(D, "commandes.log"), string.Join(" ", a) + Environment.NewLine);
        string cmd = a.Length > 0 ? a[0] : "";
        string reste = string.Join(" ", a.Skip(1).ToArray());
        bool installe = V("installe") == "1";
        string fixture = Path.Combine(D, "fixture.tar");

        switch (cmd)
        {
            case "start-server":
                return 0;
            case "get-state":
                if (V("etat") == "unauthorized") { Console.Error.WriteLine("error: device unauthorized."); return 1; }
                Console.WriteLine("device");
                return 0;
            case "install":
                Console.WriteLine("Performing Streamed Install");
                if (installe && V("cle") != "nouvelle")
                {
                    Console.WriteLine("adb: failed to install app.apk: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package mg.ecoledimanche.presences signatures do not match newer version; ignoring!]");
                    return 1;
                }
                E["installe"] = "1"; E["cle"] = "nouvelle";
                if (!installe) E["donnees"] = "0";
                Enregistrer();
                Console.WriteLine("Success");
                return 0;
            case "uninstall":
                E["installe"] = "0"; E["donnees"] = "0"; Enregistrer();
                Console.WriteLine("Success");
                return 0;
            case "pull":
                if (!File.Exists(Tel(a[1]))) { Console.Error.WriteLine("adb: error: remote object does not exist"); return 1; }
                File.Copy(Tel(a[1]), a[2], true);
                Console.WriteLine("1 file pulled");
                return 0;
            case "push":
                File.Copy(a[1], Tel(a[2]), true);
                Console.WriteLine("1 file pushed");
                return 0;
            case "exec-out":
                if (reste.StartsWith("stat"))
                {
                    long taille = new FileInfo(Tel(a[a.Length - 1])).Length;
                    if (V("taille_fausse") == "1") taille -= 10;
                    Console.Out.Write(taille + "\n");
                    return 0;
                }
                return 1;
            case "shell":
                if (reste.StartsWith("pm path")) { if (installe) Console.WriteLine("package:/data/app/base.apk"); return 0; }
                if (reste.Contains("tar -cf -"))
                {
                    if (V("donnees") != "1") return 1;
                    File.Copy(fixture, Tel(TMP), true);
                    return 0;
                }
                if (reste.StartsWith("tar -tf"))
                {
                    if (!File.Exists(Tel(TMP))) { Console.WriteLine("tar: " + TMP + ": No such file or directory"); return 1; }
                    foreach (var l in File.ReadAllLines(Path.Combine(D, "fixture-liste.txt"))) Console.Out.Write(l + "\n");
                    return 0;
                }
                if (reste.Contains("tar -xf -"))
                {
                    if (File.Exists(Tel(TMP)) && File.ReadAllBytes(Tel(TMP)).SequenceEqual(File.ReadAllBytes(fixture)))
                    {
                        E["donnees"] = "1"; E["restaure"] = "1"; Enregistrer();
                    }
                    return 0;
                }
                if (reste.StartsWith("run-as") && reste.Contains(" ls ") && reste.Contains("/databases"))
                {
                    if (V("donnees") == "1") Console.WriteLine("ecole_dimanche.db");
                    return 0;
                }
                if (reste.StartsWith("run-as") && reste.EndsWith(" ls")) return installe ? 0 : 1;
                if (reste.StartsWith("rm -f")) { if (File.Exists(Tel(TMP))) File.Delete(Tel(TMP)); return 0; }
                return 0; // am start, am force-stop, mkdir, rm -rf, tar --help
        }
        return 0;
    }
}
