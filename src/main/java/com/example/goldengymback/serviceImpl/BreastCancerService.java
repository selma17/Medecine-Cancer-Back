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
        "Analyse rigoureusement les éléments sémiologiques fournis et établis la classification " +
        "ACR BI-RADS 2013 ainsi que la conduite à tenir pour chaque sein concerné.\n\n" +

        "DONNÉES DISPONIBLES :\n" +
        "Les données peuvent inclure : une mammographie seule, une échographie seule, " +
        "ou une mammographie complétée par une échographie. " +
        "Analyse ce qui est disponible sans imposer la présence d'un examen complémentaire. " +
        "Si l'échographie est présente en complément de la mammographie, elle décrit les MÊMES " +
        "masses — ne jamais compter les masses en double. " +
        "Les microcalcifications et la distorsion architecturale peuvent être visibles uniquement " +
        "en mammographie sans être retrouvées en échographie — c'est normal.\n\n" +

        "RÈGLES DE CLASSIFICATION :\n" +
        "- Si un seul sein présente des anomalies, ne classer que ce sein.\n" +
        "- Si les deux seins présentent des anomalies, classer chacun séparément.\n" +
        "- Plusieurs lésions dans un même sein : retenir la classification LA PLUS PÉJORATIVE.\n" +
        "- Une masse solide d'allure bénigne (ovale, contours circonscrits, orientation parallèle, " +
        "renforcement postérieur) = ACR 3, PAS ACR 2.\n" +
        "- Des microcalcifications suspectes SANS masse = au moins ACR 4.\n" +
        "- Une adénopathie axillaire associée à une masse AGGRAVE la classification du sein concerné.\n" +
        "- Une distorsion architecturale en dehors d'une cicatrice connue = au moins ACR 4.\n\n" +

        "RÈGLE CRITIQUE D'INDÉPENDANCE ENTRE LES SEINS :\n" +
        "Chaque sein est classé UNIQUEMENT sur la base de SES PROPRES anomalies. " +
        "Les anomalies d'un sein ne doivent JAMAIS influencer la classification de l'autre sein. " +
        "Exemple : sein droit avec masse + microcalcifications suspectes → ACR 4 ou 5. " +
        "Sein gauche avec uniquement une masse bénigne → ACR 3, jamais ACR 4.\n\n" +

        "=== RÉFÉRENCE : Classification ACR BI-RADS en MAMMOGRAPHIE ===\n" +
        "ACR 1 : Mammographie normale.\n" +
        "ACR 2 : Lésions bénignes (VPP 0%) : masses rondes avec calcifications grossières " +
        "(kyste, adénofibrome), ganglion intra mammaire, kystes typiques en échographie, " +
        "masse de densité mixte (hamartome, lipome, galactocèle, kyste huileux), " +
        "cicatrice connue, calcifications bénignes.\n" +
        "ACR 3 : Lésions probablement bénignes (VPP ≤ 2%) : calcifications rondes ou amorphes " +
        "peu nombreuses, petits amas ronds ou ovales de calcifications polymorphes, " +
        "masse bien circonscrite ronde ou ovale sans micro-lobulations, " +
        "asymétrie focale de densité à contours concaves ou mélangée.\n" +
        "ACR 4 : Lésions indéterminées (VPP entre 2 et 95%) : calcifications rondes nombreuses " +
        "et/ou groupées aux contours irréguliers, calcifications amorphes ou poussiéreuses " +
        "groupées et nombreuses, calcifications grossières hétérogènes peu nombreuses, " +
        "calcifications fines polymorphes, distorsion architecturale en dehors d'une cicatrice connue.\n" +
        "ACR 5 : Lésions typiquement malignes (VPP > 95%) : calcifications fines linéaires ou " +
        "ramifiées, calcifications grossières hétérogènes ou fines polymorphes nombreuses et " +
        "groupées, calcifications groupées avec distribution linéaire ou segmentaire, " +
        "calcifications associées à une distorsion architecturale ou une masse, " +
        "augmentation du nombre de calcifications suspectes, masses aux contours flous, " +
        "irréguliers ou spiculés.\n\n" +

        "=== RÉFÉRENCE : Classification BI-RADS ACR en ÉCHOGRAPHIE ===\n" +
        "ACR 1 : Échographie normale.\n" +
        "ACR 2 : Lésions bénignes : kystes simples, ganglion intra mammaire, implant, " +
        "fibroadénome, cicatrice stable.\n" +
        "ACR 3 : Masse solide d'allure bénigne (contours réguliers, forme ovale, " +
        "échostructure homogène, renforcement postérieur, orientation parallèle). " +
        "Kystes compliqués, échogènes homogènes, amas de microkystes accolés.\n" +
        "ACR 4 : 4A = lésions pour lesquelles il manque un critère pour classer en ACR 3. " +
        "4B = lésions à risque intermédiaire, nécessitant une discussion radio-histologique " +
        "et une surveillance rapprochée. " +
        "4C = lésions à haut risque, un critère manquant pour ACR 5.\n" +
        "ACR 5 : Masse aux contours flous ou irréguliers, masse de contours spiculés. " +
        "Un complément par micro-biopsie est nécessaire.\n\n" +

        "=== RÉFÉRENCE : Classification des MICROCALCIFICATIONS (morphologie × distribution) ===\n" +
        "Rondes/punctiformes : diffuses=ACR2 | groupées/régionales=ACR3 | linéaires/segmentaires=ACR4A\n" +
        "Grossières hétérogènes : diffuses=ACR2 | groupées/régionales=ACR4B | linéaires/segmentaires=ACR4C\n" +
        "Amorphes/pléiomorphes : diffuses=ACR2/3 | groupées/régionales=ACR4B | linéaires/segmentaires=ACR4C\n" +
        "Linéaires : diffuses=ACR4A | groupées/régionales=ACR4C | linéaires/segmentaires=ACR5\n\n" +

        "FORMAT OBLIGATOIRE en fin de réponse :\n" +
        "ACR sein droit : X. Action recommandée : [action]\n" +
        "ACR sein gauche : X. Action recommandée : [action]\n\n" +
        "X = 1, 2, 3, 4A, 4B, 4C ou 5\n" +
        "[action] = Surveillance après 6 mois ou Biopsie.";

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
        // Masses mammographie
        if (scan.getMassesMammographie() != null && scan.getMassesMammographie().stream()
                .anyMatch(m -> m.getSein() != null && m.getSein().toLowerCase().startsWith(side)))
            return true;
        // Masses échographie
        if (scan.getMassesEchostructure() != null && scan.getMassesEchostructure().stream()
                .anyMatch(m -> m.getSein() != null && m.getSein().toLowerCase().startsWith(side)))
            return true;
        // Calcifications localisées sur ce sein
        if (scan.isCalcifications() && matchSide(scan.getLocalisationCalcifications(), side))
            return true;
        // Distorsion localisée sur ce sein
        if (scan.isDistorsionArchitecturale() && matchSide(scan.getLocalisationDistorsion(), side))
            return true;
        // Asymétrie localisée sur ce sein
        if (scan.isAsymetrie() && matchSide(scan.getLocalisationAsymetrie(), side))
            return true;
        // Signes associés mammographie localisés sur ce sein
        if (scan.getSignesAssociesMammographie() != null && scan.getLocalisationsSignesMammographie() != null) {
            for (int i = 0; i < scan.getLocalisationsSignesMammographie().size(); i++) {
                if (matchSide(scan.getLocalisationsSignesMammographie().get(i), side)) return true;
            }
        }
        // Signes associés échographie localisés sur ce sein
        if (scan.getSignesAssociesEchostructure() != null && scan.getLocalisationsSignesEchostructure() != null) {
            for (int i = 0; i < scan.getLocalisationsSignesEchostructure().size(); i++) {
                if (matchSide(scan.getLocalisationsSignesEchostructure().get(i), side)) return true;
            }
        }
        // Cas spéciaux localisés sur ce sein
        if (scan.getCasSpeciaux() != null) {
            for (var cas : scan.getCasSpeciaux()) {
                if (matchSide(cas.getLocalisation(), side)) return true;
            }
        }
        // Adénopathie localisée sur ce sein
        if (notEmpty(scan.getAdenopathieLocalisation())) {
            String aLoc = scan.getAdenopathieLocalisation().toLowerCase();
            if (aLoc.contains("bilatérale") || aLoc.contains("bilateral") || aLoc.contains(side))
                return true;
        }
        return false;
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

        // Adénopathie axillaire — détails (si rattachée à ce sein)
        if (notEmpty(scan.getAdenopathieLocalisation())) {
            String aLoc = scan.getAdenopathieLocalisation().toLowerCase();
            boolean matchAdeno = aLoc.contains("bilatérale") || aLoc.contains("bilateral")
                || (side.equals("droit") && aLoc.contains("droit"))
                || (side.equals("gauche") && aLoc.contains("gauche"));
            if (matchAdeno) {
                b.append("  Adénopathie axillaire :\n");
                b.append("    Localisation : ").append(scan.getAdenopathieLocalisation()).append("\n");
                if (notEmpty(scan.getAdenopathieChaineBerg()))
                    b.append("    Chaîne de Berg : ").append(scan.getAdenopathieChaineBerg()).append("\n");
                if (notEmpty(scan.getAdenopathieNombre()))
                    b.append("    Nombre : ").append(scan.getAdenopathieNombre()).append("\n");
                if (notEmpty(scan.getAdenopathieMesure()))
                    b.append("    Mesure : ").append(scan.getAdenopathieMesure()).append(" mm\n");
            }
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