package com.example.goldengymback.serviceImpl;

import com.example.goldengymback.model.MammaryScan;
import com.example.goldengymback.repository.MammaryScanRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class BreastCancerService implements com.example.goldengymback.service.BreastCancerService {

    @Value("${openai.api.key}")
    private String openaiApiKey;

    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String MODEL = "gpt-4o-mini";

    @Autowired
    private MammaryScanRepo mammaryScanRepository;

    private static final String SYSTEM_PROMPT =
        "Tu es un radiologue expert spécialisé en imagerie mammaire. " +
        "Tu établis la classification ACR BI-RADS 2013 et la conduite à tenir pour chaque sein, " +
        "de façon rigoureuse et reproductible.\n\n" +

        "MÉTHODE D'ANALYSE OBLIGATOIRE (suis ces étapes dans l'ordre) :\n" +
        "1. Les données te sont fournies déjà regroupées par sein (SEIN DROIT, SEIN GAUCHE). " +
        "Traite CHAQUE sein séparément, l'un après l'autre, comme deux dossiers indépendants.\n" +
        "2. Pour un sein donné, n'utilise QUE les données listées sous ce sein. " +
        "N'utilise JAMAIS une donnée de l'autre sein, même indirectement.\n" +
        "3. Analyse TOUTES les masses du sein (il peut y en avoir 0, 1, 2 ou plus). " +
        "Pour chaque masse, évalue forme, contours, densité/échostructure, orientation et calcifications.\n" +
        "4. Intègre les signes du même sein (microcalcifications, distorsion architecturale, " +
        "asymétrie, signes associés, cas spéciaux) UNIQUEMENT s'ils appartiennent à ce sein.\n" +
        "5. Mammographie et échographie décrivent les MÊMES masses sous deux angles : " +
        "ne jamais compter une masse en double.\n" +
        "6. Si le sein contient plusieurs lésions, retiens la classification LA PLUS PÉJORATIVE " +
        "des lésions de CE sein.\n" +
        "7. Si un sein n'a aucune anomalie listée, ne le classe pas.\n\n" +

        "RÈGLE D'INDÉPENDANCE ABSOLUE ENTRE LES SEINS :\n" +
        "La présence de microcalcifications suspectes, d'une distorsion architecturale, " +
        "de signes de malignité ou de toute anomalie dans UN sein n'a AUCUN effet sur la " +
        "classification de l'autre sein. " +
        "Exemple : sein droit = masse + microcalcifications suspectes → ACR 4 ou 5 ; " +
        "sein gauche = masse bénigne isolée → ACR 2 ou 3, JAMAIS influencé par le sein droit. " +
        "Les deux seins peuvent avoir des classifications totalement différentes.\n\n" +

        "RÈGLES DE CLASSIFICATION BI-RADS 2013 (repères) :\n" +
        "- ACR 1 : examen normal, aucune anomalie.\n" +
        "- ACR 2 : anomalie typiquement bénigne (ex : kyste simple, masse ovale circonscrite " +
        "parallèle isoéchogène, calcifications bénignes typiques).\n" +
        "- ACR 3 : anomalie probablement bénigne (VPP malignité < 2 %), surveillance rapprochée.\n" +
        "- ACR 4 : anomalie suspecte (4A faible, 4B intermédiaire, 4C forte suspicion), biopsie.\n" +
        "- ACR 5 : très évocateur de malignité, biopsie.\n" +
        "Critères péjoratifs : contours indistincts/spiculés/microlobulés, orientation non parallèle, " +
        "distorsion architecturale, microcalcifications suspectes, atténuation postérieure, " +
        "rétraction cutanée. Critères rassurants : forme ovale/ronde, contours circonscrits, " +
        "orientation parallèle, renforcement postérieur.\n\n" +

        "FORMAT OBLIGATOIRE en fin de réponse (uniquement pour le(s) sein(s) concerné(s)) :\n" +
        "ACR sein droit : X. Action recommandée : [action]\n" +
        "ACR sein gauche : X. Action recommandée : [action]\n\n" +
        "X = 1, 2, 3, 4A, 4B, 4C ou 5\n" +
        "[action] = Surveillance après 6 mois ou Biopsie" ;

    // ─── Helpers ──────────────────────────────────────────────────────────────
    private static boolean notEmpty(String s) {
        return s != null && !s.isBlank();
    }

    private static void appendIfNotEmpty(StringBuilder p, String label, String value) {
        if (notEmpty(value)) p.append(label).append(value).append("\n");
    }

    // ─── Points d'entrée ──────────────────────────────────────────────────────
    @Override
    public String getAcrScore(Long scanId) {
        MammaryScan scan = mammaryScanRepository.findById(scanId)
                .orElseThrow(() -> new RuntimeException("Scan not found for ID: " + scanId));
        String prompt = createPrompt(scan);
        String aiResponse = callOpenAiApi(prompt);
        updateScanWithAiResponse(aiResponse, scan);
        return aiResponse;
    }

    @Override
    public String getDiagnosticFromData(String description) {
        String aiResponse = callOpenAiApi(description);
        if (aiResponse == null || aiResponse.trim().isEmpty())
            throw new RuntimeException("Réponse IA vide ou invalide.");
        return aiResponse;
    }

    // ─── Appel API OpenAI ──────────────────────────────────────────────────────
    private String callOpenAiApi(String prompt) {
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + openaiApiKey);
        headers.set("Content-Type", "application/json");

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", MODEL);
        requestBody.put("messages", List.of(
            Map.of("role", "system", "content", SYSTEM_PROMPT),
            Map.of("role", "user", "content", prompt)
        ));
        requestBody.put("max_tokens", 1000);
        requestBody.put("temperature", 0);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                OPENAI_API_URL, HttpMethod.POST, entity, Map.class);
            Map<String, Object> body = response.getBody();
            if (body != null && body.containsKey("choices")) {
                List<Map<String, Object>> choices = (List<Map<String, Object>>) body.get("choices");
                if (!choices.isEmpty()) {
                    Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                    String content = (String) message.get("content");
                    if (content != null && !content.trim().isEmpty()) return content;
                }
            }
            throw new RuntimeException("Réponse invalide de l'API OpenAI");
        } catch (HttpClientErrorException e) {
            throw new RuntimeException("Erreur OpenAI HTTP " + e.getStatusCode() + " : " + e.getResponseBodyAsString());
        } catch (Exception e) {
            throw new RuntimeException("Erreur appel OpenAI : " + e.getMessage());
        }
    }

    // ─── Parsing et sauvegarde ─────────────────────────────────────────────────
    private void updateScanWithAiResponse(String aiResponse, MammaryScan scan) {
        Pattern droitPattern = Pattern.compile(
            "ACR\\s+sein\\s+droit[e]?\\s*[:\\-]?\\s*([0-9][ABC]?)\\s*\\.?\\s*Action\\s+recommand[ée]+e?\\s*[:\\-]?\\s*([^\\n]+)",
            Pattern.CASE_INSENSITIVE);
        Pattern gauchePattern = Pattern.compile(
            "ACR\\s+sein\\s+gauche\\s*[:\\-]?\\s*([0-9][ABC]?)\\s*\\.?\\s*Action\\s+recommand[ée]+e?\\s*[:\\-]?\\s*([^\\n]+)",
            Pattern.CASE_INSENSITIVE);

        List<String> validConduites = List.of(
            "Surveillance", "Biopsie", "Ablation chirurgicale", "Traitement médical");

        String acrDroit = null, conduiteDroit = null;
        String acrGauche = null, conduiteGauche = null;

        Matcher dm = droitPattern.matcher(aiResponse);
        Matcher gm = gauchePattern.matcher(aiResponse);

        if (dm.find()) { acrDroit = dm.group(1).trim(); conduiteDroit = normalizeConduite(dm.group(2).trim(), validConduites); }
        if (gm.find()) { acrGauche = gm.group(1).trim(); conduiteGauche = normalizeConduite(gm.group(2).trim(), validConduites); }

        // Fallback global → distribuer aux seins concernés
        if (acrDroit == null && acrGauche == null) {
            Pattern fb = Pattern.compile(
                "ACR\\s*[:\\-]?\\s*(\\d[ABC]?)\\s*\\.?\\s*Action\\s+recommand[ée]+e?\\s*[:\\-]?\\s*([^\\n]+)",
                Pattern.CASE_INSENSITIVE);
            Matcher fm = fb.matcher(aiResponse);
            if (fm.find()) {
                String acrG = fm.group(1).trim();
                String catG = normalizeConduite(fm.group(2).trim(), validConduites);
                scan.setConclusionIA(acrG);
                scan.setConduiteATenir(catG);
                scan.setFullAiResponse(aiResponse);
                boolean hd = hasSeins(scan, "droit"), hg = hasSeins(scan, "gauche");
                if (!hd && !hg) { hd = true; hg = true; }
                if (hd) { scan.setAcrDroit(acrG);  scan.setRecommandationDroit(catG);  }
                if (hg) { scan.setAcrGauche(acrG); scan.setRecommandationGauche(catG); }
                mammaryScanRepository.save(scan);
                return;
            }
            throw new RuntimeException("Format de réponse IA invalide. Réponse: " + aiResponse);
        }

        String acrGlobal = computeGlobalAcr(acrDroit, acrGauche);
        String catGlobal = priorityConduite(conduiteDroit, conduiteGauche, validConduites);

        scan.setConclusionIA(acrGlobal);
        scan.setConduiteATenir(catGlobal);
        scan.setAcrDroit(acrDroit);
        scan.setAcrGauche(acrGauche);
        scan.setRecommandationDroit(conduiteDroit);
        scan.setRecommandationGauche(conduiteGauche);
        scan.setFullAiResponse(aiResponse);
        mammaryScanRepository.save(scan);
    }

    private boolean hasSeins(MammaryScan scan, String side) {
        return (scan.getMassesMammographie() != null && scan.getMassesMammographie().stream()
                .anyMatch(m -> m.getSein() != null && m.getSein().toLowerCase().startsWith(side)))
            || (scan.getMassesEchostructure() != null && scan.getMassesEchostructure().stream()
                .anyMatch(m -> m.getSein() != null && m.getSein().toLowerCase().startsWith(side)));
    }

    // ─── normalizeConduite : garde la conduite complète si elle commence par un mot clé valide
    private String normalizeConduite(String raw, List<String> valid) {
        if (raw == null) return "Surveillance";
        // Nettoyer trailing point et espaces
        String cleaned = raw.trim().replaceAll("\\.$", "").trim();
        // Si la conduite commence par un mot clé valide, garder la version complète
        // (ex: "Surveillance — mammographie et échographie à 6 mois")
        for (String v : valid) {
            if (cleaned.toLowerCase().startsWith(v.toLowerCase())) {
                return cleaned;
            }
        }
        // Fallback : chercher le mot clé n'importe où dans la réponse
        for (String v : valid) {
            if (cleaned.toLowerCase().contains(v.toLowerCase())) return v;
        }
        return "Surveillance";
    }

    private String computeGlobalAcr(String d, String g) {
        if (d == null) return g != null ? g : "1";
        if (g == null) return d;
        int nd = Character.getNumericValue(d.charAt(0));
        int ng = Character.getNumericValue(g.charAt(0));
        if (nd > ng) return d;
        if (ng > nd) return g;
        char sd = d.length() > 1 ? d.charAt(1) : '0';
        char sg = g.length() > 1 ? g.charAt(1) : '0';
        return sd >= sg ? d : g;
    }

    private String priorityConduite(String c1, String c2, List<String> valid) {
        if (c1 == null) return c2 != null ? c2 : "Surveillance";
        if (c2 == null) return c1;
        Map<String, Integer> p = Map.of(
            "Surveillance", 1, "Traitement médical", 2,
            "Ablation chirurgicale", 3, "Biopsie", 4);
        return p.getOrDefault(c1, 1) >= p.getOrDefault(c2, 1) ? c1 : c2;
    }

    // ─── Détermine à quel sein appartient une localisation/sein textuel ─────────
    private static boolean matchSide(String value, String side) {
        if (value == null) return false;
        String v = value.toLowerCase();
        if (side.equals("droit"))  return v.contains("droit") || v.endsWith("d") || v.contains(" d ") || v.contains("/d");
        if (side.equals("gauche")) return v.contains("gauche") || v.endsWith("g") || v.contains(" g ") || v.contains("/g");
        return false;
    }

    // ─── Construit le bloc de données pour UN sein donné ────────────────────────
    // soloSide = true si ce sein est le SEUL concerné par l'examen : dans ce cas
    // les signes sans côté explicite lui sont rattachés.
    private void appendSeinBlock(StringBuilder p, MammaryScan scan, String side, String titre, boolean soloSide) {
        StringBuilder b = new StringBuilder();

        // Un signe appartient à ce sein si sa localisation matche le côté,
        // OU si ce sein est le seul concerné (soloSide) et la localisation
        // ne désigne pas explicitement l'autre côté.
        String autre = side.equals("droit") ? "gauche" : "droit";
        java.util.function.BiPredicate<String, String> belongs = (loc, s) -> {
            if (matchSide(loc, s)) return true;
            if (soloSide && !matchSide(loc, autre)) return true;
            return false;
        };

        // Masses mammographie de ce sein
        java.util.List<Integer> idxMammo = new java.util.ArrayList<>();
        if (scan.getMassesMammographie() != null) {
            for (int i = 0; i < scan.getMassesMammographie().size(); i++) {
                var m = scan.getMassesMammographie().get(i);
                if (m.getSein() != null && m.getSein().toLowerCase().startsWith(side)) idxMammo.add(i);
            }
        }
        if (!idxMammo.isEmpty()) {
            b.append("  Masses (mammographie) — ").append(idxMammo.size()).append(" :\n");
            int n = 1;
            for (int i : idxMammo) {
                var m = scan.getMassesMammographie().get(i);
                b.append("    Masse ").append(n++).append(" :");
                if (notEmpty(m.getLocalisation())) b.append(" Loc=").append(m.getLocalisation());
                if (notEmpty(m.getForme()))         b.append(" | Forme=").append(m.getForme());
                if (notEmpty(m.getContours()))      b.append(" | Contours=").append(m.getContours());
                if (notEmpty(m.getDensite()))       b.append(" | Densité=").append(m.getDensite());
                b.append("\n");
            }
        }

        // Masses échographie de ce sein
        java.util.List<Integer> idxEcho = new java.util.ArrayList<>();
        if (scan.getMassesEchostructure() != null) {
            for (int i = 0; i < scan.getMassesEchostructure().size(); i++) {
                var m = scan.getMassesEchostructure().get(i);
                if (m.getSein() != null && m.getSein().toLowerCase().startsWith(side)) idxEcho.add(i);
            }
        }
        if (!idxEcho.isEmpty()) {
            b.append("  Masses (échographie — mêmes lésions, ne pas compter en double) — ")
             .append(idxEcho.size()).append(" :\n");
            int n = 1;
            for (int i : idxEcho) {
                var m = scan.getMassesEchostructure().get(i);
                b.append("    Masse ").append(n++).append(" :");
                if (notEmpty(m.getLocalisation()))                     b.append(" Loc=").append(m.getLocalisation());
                if (notEmpty(m.getMesure()))                            b.append(" | ").append(m.getMesure()).append("mm");
                if (notEmpty(m.getDistanceCentre()))                    b.append(" | Dist.mamelon=").append(m.getDistanceCentre()).append("mm");
                if (notEmpty(m.getForme()))                             b.append(" | Forme=").append(m.getForme());
                if (notEmpty(m.getContours()))                          b.append(" | Contours=").append(m.getContours());
                if (notEmpty(m.getDensite()))                           b.append(" | Écho=").append(m.getDensite());
                if (notEmpty(m.getOrientation()))                       b.append(" | Orient=").append(m.getOrientation());
                if (notEmpty(m.getComportementDesFaisceauxUltrasons())) b.append(" | Comport=").append(m.getComportementDesFaisceauxUltrasons());
                if (notEmpty(m.getCalcifications()))                    b.append(" | Calcif=").append(m.getCalcifications());
                b.append("\n");
            }
        }

        // Asymétrie (si rattachée à ce sein)
        if (scan.isAsymetrie() && belongs.test(scan.getLocalisationAsymetrie(), side)) {
            b.append("  Asymétrie : Oui");
            if (notEmpty(scan.getTypeAsymetrie()))         b.append(" — ").append(scan.getTypeAsymetrie());
            if (notEmpty(scan.getLocalisationAsymetrie())) b.append(" (").append(scan.getLocalisationAsymetrie()).append(")");
            b.append("\n");
        }

        // Distorsion architecturale (si rattachée à ce sein)
        if (scan.isDistorsionArchitecturale() && belongs.test(scan.getLocalisationDistorsion(), side)) {
            b.append("  Distorsion architecturale : Oui");
            if (notEmpty(scan.getOptionDistorsionArchitecturale())) b.append(" — ").append(scan.getOptionDistorsionArchitecturale());
            if (notEmpty(scan.getLocalisationDistorsion()))          b.append(" (").append(scan.getLocalisationDistorsion()).append(")");
            b.append("\n");
        }

        // Calcifications (si rattachées à ce sein)
        if (scan.isCalcifications() && belongs.test(scan.getLocalisationCalcifications(), side)) {
            b.append("  Calcifications : Oui");
            if (notEmpty(scan.getLocalisationCalcifications())) b.append(" (").append(scan.getLocalisationCalcifications()).append(")");
            b.append("\n");
            if (notEmpty(scan.getTypesCalcifications()))             b.append("    Type : ").append(scan.getTypesCalcifications()).append("\n");
            if (notEmpty(scan.getCalcificationsBenignes()))          b.append("    Bénignes : ").append(scan.getCalcificationsBenignes()).append("\n");
            if (notEmpty(scan.getCalcificationsSuspectes()))         b.append("    Suspectes : ").append(scan.getCalcificationsSuspectes()).append("\n");
            if (notEmpty(scan.getDistributionMicrocalcifications())) b.append("    Distribution : ").append(scan.getDistributionMicrocalcifications()).append("\n");
        }

        // Signes associés mammographie (si localisés sur ce sein)
        if (scan.getSignesAssociesMammographie() != null) {
            List<String> locs = scan.getLocalisationsSignesMammographie();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < scan.getSignesAssociesMammographie().size(); i++) {
                String loc = (locs != null && i < locs.size()) ? locs.get(i) : null;
                if (belongs.test(loc, side)) {
                    sb.append("    • ").append(scan.getSignesAssociesMammographie().get(i));
                    if (notEmpty(loc)) sb.append(" (").append(loc).append(")");
                    sb.append("\n");
                }
            }
            if (sb.length() > 0) b.append("  Signes associés (mammographie) :\n").append(sb);
        }

        // Signes associés échographie (si localisés sur ce sein)
        if (scan.getSignesAssociesEchostructure() != null) {
            List<String> locs = scan.getLocalisationsSignesEchostructure();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < scan.getSignesAssociesEchostructure().size(); i++) {
                String loc = (locs != null && i < locs.size()) ? locs.get(i) : null;
                if (belongs.test(loc, side)) {
                    sb.append("    • ").append(scan.getSignesAssociesEchostructure().get(i));
                    if (notEmpty(loc)) sb.append(" (").append(loc).append(")");
                    sb.append("\n");
                }
            }
            if (sb.length() > 0) b.append("  Signes associés (échographie) :\n").append(sb);
        }

        // Cas spéciaux (si localisés sur ce sein)
        if (scan.getCasSpeciaux() != null) {
            StringBuilder sb = new StringBuilder();
            for (var cas : scan.getCasSpeciaux()) {
                if (belongs.test(cas.getLocalisation(), side)) {
                    sb.append("    • ").append(cas.getNom());
                    if (notEmpty(cas.getLocalisation())) sb.append(" (").append(cas.getLocalisation()).append(")");
                    sb.append("\n");
                }
            }
            if (sb.length() > 0) b.append("  Cas spéciaux :\n").append(sb);
        }

        // N'écrire le bloc que s'il contient des données
        if (b.length() > 0) {
            p.append("\n").append(titre).append("\n");
            p.append(b);
        }
    }

    // ─── Construction du prompt ────────────────────────────────────────────────
    private String createPrompt(MammaryScan scan) {
        StringBuilder p = new StringBuilder();
        p.append("Analyse cet examen mammaire et fournis la classification BI-RADS ACR 2013.\n");
        p.append("Les champs absents signifient que la donnée n'a pas été recueillie — classe avec ce qui est disponible.\n");
        p.append("Les données sont regroupées PAR SEIN. Traite chaque sein séparément et indépendamment.\n");

        // Densité / échostructure = communes (caractéristiques globales du tissu)
        StringBuilder global = new StringBuilder();
        appendIfNotEmpty(global, "Densité mammaire : ", scan.getDensiteMammaire());
        appendIfNotEmpty(global, "Échostructure : ", scan.getEchostructureMammaire());
        if (global.length() > 0) {
            p.append("\n=== CONTEXTE GÉNÉRAL (commun aux deux seins) ===\n").append(global);
        }

        boolean hasDroit  = hasSeins(scan, "droit");
        boolean hasGauche = hasSeins(scan, "gauche");
        if (!hasDroit && !hasGauche) { hasDroit = true; hasGauche = true; }

        // Un seul sein concerné → les signes sans côté explicite lui sont rattachés
        boolean droitSolo  = hasDroit && !hasGauche;
        boolean gaucheSolo = hasGauche && !hasDroit;

        if (hasDroit)  appendSeinBlock(p, scan, "droit",  "===== SEIN DROIT =====",  droitSolo);
        if (hasGauche) appendSeinBlock(p, scan, "gauche", "===== SEIN GAUCHE =====", gaucheSolo);

        p.append("\nClasse chaque sein ci-dessus de façon INDÉPENDANTE, sur ses seules données.\n");
        p.append("Termine ta réponse OBLIGATOIREMENT par :\n");
        if (hasDroit)  p.append("ACR sein droit : X. Action recommandée : [action]\n");
        if (hasGauche) p.append("ACR sein gauche : X. Action recommandée : [action]\n");

        return p.toString();
    }
}