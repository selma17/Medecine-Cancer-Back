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

    @Value("${openrouter.api.key}")
    private String openrouterApiKey;

    private static final String OPENROUTER_API_URL = "https://openrouter.ai/api/v1/chat/completions";

    // Modèles par ordre de priorité — si le premier est rate-limité, on essaie le suivant
    private static final String[] MODELS = {
        "google/gemini-2.0-flash-exp:free",
        "meta-llama/llama-3.3-70b-instruct:free",
        "deepseek/deepseek-r1:free"
    };

    @Autowired
    private MammaryScanRepo mammaryScanRepository;

    private static final String SYSTEM_PROMPT =
        "Tu es un radiologue expert en imagerie mammaire spécialisé dans la classification BI-RADS ACR 2013. " +
        "Réponds toujours en français.\n\n" +
        "RÈGLE FONDAMENTALE : La mammographie et l'échographie examinent les MÊMES seins. " +
        "Les masses décrites dans les deux modalités sont les MÊMES masses. Ne jamais les compter en double.\n\n" +
        "CLASSIFICATION BI-RADS ACR 2013 :\n" +
        "- ACR 1 : Examen normal — Surveillance\n" +
        "- ACR 2 : Anomalie bénigne certaine — Surveillance\n" +
        "- ACR 3 : Probablement bénigne (malignité < 2%) — Surveillance\n" +
        "- ACR 4A : Faible suspicion (2-10%) — Biopsie\n" +
        "- ACR 4B : Suspicion intermédiaire (10-50%) — Biopsie\n" +
        "- ACR 4C : Suspicion élevée (50-95%) — Biopsie\n" +
        "- ACR 5 : Hautement suspect (> 95%) — Biopsie\n\n" +
        "RÈGLES DE CLASSIFICATION :\n" +
        "- Masse ovale + contours circonscrits = ACR 3 minimum\n" +
        "- Masse irrégulière ou contours spiculés = ACR 4C minimum\n" +
        "- Calcifications suspectes = ACR 4 minimum\n" +
        "- Plusieurs lésions dans un sein : retenir la plus péjorative\n\n" +
        "CHAQUE MASSE INDIQUE SON SEIN (DROIT ou GAUCHE). Utilise cette information directement.\n\n" +
        "FORMAT OBLIGATOIRE — dernières lignes de ta réponse :\n" +
        "ACR sein droit : X. Action recommandée : [action]\n" +
        "ACR sein gauche : X. Action recommandée : [action]\n\n" +
        "X = 1, 2, 3, 4A, 4B, 4C ou 5\n" +
        "[action] = exactement un de : Surveillance, Biopsie, Ablation chirurgicale, Traitement médical\n\n" +
        "EXEMPLE CORRECT :\n" +
        "ACR sein droit : 3. Action recommandée : Surveillance\n" +
        "ACR sein gauche : 4C. Action recommandée : Biopsie\n\n" +
        "INTERDIT : philosopher, refuser, expliquer une impossibilité. " +
        "Tu as toutes les données nécessaires (SEIN indiqué sur chaque masse). Classifie directement.";

    @Override
    public String getAcrScore(Long scanId) {
        MammaryScan scan = mammaryScanRepository.findById(scanId)
                .orElseThrow(() -> new RuntimeException("Scan not found for ID: " + scanId));

        String prompt = createPrompt(scan);
        String aiResponse = callOpenRouterApiWithFallback(prompt);
        updateScanWithAiResponse(aiResponse, scan);
        return aiResponse;
    }

    @Override
    public String getDiagnosticFromData(String description) {
        String aiResponse = callOpenRouterApiWithFallback(description);
        if (aiResponse == null || aiResponse.trim().isEmpty()) {
            throw new RuntimeException("Réponse IA vide ou invalide.");
        }
        return aiResponse;
    }

    // ─── Appel avec fallback sur plusieurs modèles ─────────────────────────────
    private String callOpenRouterApiWithFallback(String prompt) {
        Exception lastException = null;
        for (String model : MODELS) {
            try {
                String result = callOpenRouterApi(prompt, model);
                if (result != null && !result.trim().isEmpty()) {
                    return result;
                }
            } catch (Exception e) {
                lastException = e;
                System.out.println("Modèle " + model + " échoué : " + e.getMessage() + " — essai suivant...");
            }
        }
        throw new RuntimeException("Tous les modèles ont échoué. Dernière erreur : " +
            (lastException != null ? lastException.getMessage() : "inconnue"));
    }

    private String callOpenRouterApi(String prompt, String model) {
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + openrouterApiKey);
        headers.set("Content-Type", "application/json");
        headers.set("HTTP-Referer", "https://srv-d7dqlh9j2pic73fplqa0.onrender.com");
        headers.set("X-Title", "CancerIA");

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", List.of(
            Map.of("role", "system", "content", SYSTEM_PROMPT),
            Map.of("role", "user", "content", prompt)
        ));
        requestBody.put("max_tokens", 1000);
        requestBody.put("temperature", 0);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                OPENROUTER_API_URL, HttpMethod.POST, entity, Map.class
            );
            Map<String, Object> responseBody = response.getBody();
            if (responseBody != null && responseBody.containsKey("choices")) {
                List<Map<String, Object>> choices = (List<Map<String, Object>>) responseBody.get("choices");
                if (!choices.isEmpty()) {
                    Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                    String content = (String) message.get("content");
                    if (content != null && !content.trim().isEmpty()) {
                        return content;
                    }
                }
            }
            throw new RuntimeException("Réponse invalide du modèle " + model);
        } catch (HttpClientErrorException e) {
            throw new RuntimeException("HTTP " + e.getStatusCode() + " pour " + model + ": " + e.getResponseBodyAsString());
        } catch (Exception e) {
            throw new RuntimeException("Erreur " + model + ": " + e.getMessage());
        }
    }

    // ─── Parsing de la réponse IA ──────────────────────────────────────────────
    private void updateScanWithAiResponse(String aiResponse, MammaryScan scan) {

        Pattern droitPattern = Pattern.compile(
            "ACR\\s+sein\\s+droit\\s*[:\\-]?\\s*([0-9][ABC]?)\\s*\\.?\\s*Action\\s+recommand[ée]+e?\\s*[:\\-]?\\s*([^\\n]+)",
            Pattern.CASE_INSENSITIVE
        );
        Pattern gauchePattern = Pattern.compile(
            "ACR\\s+sein\\s+gauche\\s*[:\\-]?\\s*([0-9][ABC]?)\\s*\\.?\\s*Action\\s+recommand[ée]+e?\\s*[:\\-]?\\s*([^\\n]+)",
            Pattern.CASE_INSENSITIVE
        );

        Matcher droitMatcher  = droitPattern.matcher(aiResponse);
        Matcher gaucheMatcher = gauchePattern.matcher(aiResponse);

        List<String> validConduites = List.of(
            "Surveillance", "Biopsie", "Ablation chirurgicale", "Traitement médical"
        );

        String acrDroit       = null;
        String conduiteDroit  = null;
        String acrGauche      = null;
        String conduiteGauche = null;

        if (droitMatcher.find()) {
            acrDroit      = droitMatcher.group(1).trim();
            conduiteDroit = normalizeConduite(droitMatcher.group(2).trim(), validConduites);
        }
        if (gaucheMatcher.find()) {
            acrGauche      = gaucheMatcher.group(1).trim();
            conduiteGauche = normalizeConduite(gaucheMatcher.group(2).trim(), validConduites);
        }

        // Fallback : format global → distribuer aux seins concernés
        if (acrDroit == null && acrGauche == null) {
            Pattern fallback = Pattern.compile(
                "ACR\\s*[:\\-]?\\s*(\\d[ABC]?)\\s*\\.?\\s*Action\\s+recommand[ée]+e?\\s*[:\\-]?\\s*([^\\n]+)",
                Pattern.CASE_INSENSITIVE
            );
            Matcher fm = fallback.matcher(aiResponse);
            if (fm.find()) {
                String acrGlobal      = fm.group(1).trim();
                String conduiteGlobal = normalizeConduite(fm.group(2).trim(), validConduites);
                scan.setConclusionIA(acrGlobal);
                scan.setConduiteATenir(conduiteGlobal);
                scan.setFullAiResponse(aiResponse);

                boolean hasDroitFallback = hasSeins(scan, "droit");
                boolean hasGaucheFallback = hasSeins(scan, "gauche");
                if (!hasDroitFallback && !hasGaucheFallback) {
                    hasDroitFallback = true;
                    hasGaucheFallback = true;
                }
                if (hasDroitFallback)  { scan.setAcrDroit(acrGlobal);  scan.setRecommandationDroit(conduiteGlobal);  }
                if (hasGaucheFallback) { scan.setAcrGauche(acrGlobal); scan.setRecommandationGauche(conduiteGlobal); }

                mammaryScanRepository.save(scan);
                return;
            }
            throw new RuntimeException("Format de réponse IA invalide. Réponse: " + aiResponse);
        }

        String acrGlobal       = computeGlobalAcr(acrDroit, acrGauche);
        String conduiteGlobale = priorityConduite(conduiteDroit, conduiteGauche, validConduites);

        scan.setConclusionIA(acrGlobal);
        scan.setConduiteATenir(conduiteGlobale);
        scan.setAcrDroit(acrDroit);
        scan.setAcrGauche(acrGauche);
        scan.setRecommandationDroit(conduiteDroit);
        scan.setRecommandationGauche(conduiteGauche);
        scan.setFullAiResponse(aiResponse);
        mammaryScanRepository.save(scan);
    }

    private boolean hasSeins(MammaryScan scan, String side) {
        return (scan.getMassesMammographie() != null && scan.getMassesMammographie().stream()
                .anyMatch(m -> m.getSein() != null && m.getSein().toLowerCase().contains(side)))
            || (scan.getMassesEchostructure() != null && scan.getMassesEchostructure().stream()
                .anyMatch(m -> m.getSein() != null && m.getSein().toLowerCase().contains(side)));
    }

    private String normalizeConduite(String raw, List<String> validConduites) {
        if (raw == null) return "Surveillance";
        String cleaned = raw.split("\\.")[0].trim();
        for (String valid : validConduites) {
            if (cleaned.equalsIgnoreCase(valid)) return valid;
        }
        for (String valid : validConduites) {
            if (cleaned.toLowerCase().contains(valid.toLowerCase())) return valid;
        }
        return "Surveillance";
    }

    private String computeGlobalAcr(String acrDroit, String acrGauche) {
        if (acrDroit == null)  return acrGauche != null ? acrGauche : "1";
        if (acrGauche == null) return acrDroit;
        int numDroit  = Character.getNumericValue(acrDroit.charAt(0));
        int numGauche = Character.getNumericValue(acrGauche.charAt(0));
        if (numDroit > numGauche) return acrDroit;
        if (numGauche > numDroit) return acrGauche;
        char subDroit  = acrDroit.length()  > 1 ? acrDroit.charAt(1)  : '0';
        char subGauche = acrGauche.length() > 1 ? acrGauche.charAt(1) : '0';
        return subDroit >= subGauche ? acrDroit : acrGauche;
    }

    private String priorityConduite(String c1, String c2, List<String> validConduites) {
        if (c1 == null) return c2 != null ? c2 : "Surveillance";
        if (c2 == null) return c1;
        Map<String, Integer> priority = Map.of(
            "Surveillance", 1, "Traitement médical", 2,
            "Ablation chirurgicale", 3, "Biopsie", 4
        );
        return priority.getOrDefault(c1, 1) >= priority.getOrDefault(c2, 1) ? c1 : c2;
    }

    // ─── Construction du prompt ────────────────────────────────────────────────
    private String createPrompt(MammaryScan scan) {
        StringBuilder p = new StringBuilder();
        p.append("Analyse cet examen mammaire :\n\n");

        p.append("=== MAMMOGRAPHIE ===\n");
        p.append("Densité : ").append(scan.getDensiteMammaire()).append("\n");
        p.append("Asymétrie : ").append(scan.isAsymetrie() ? "Oui — " + scan.getTypeAsymetrie() +
            (scan.getLocalisationAsymetrie() != null ? " (" + scan.getLocalisationAsymetrie() + ")" : "") : "Non").append("\n");
        p.append("Distorsion : ").append(scan.isDistorsionArchitecturale() ? "Oui" +
            (scan.getLocalisationDistorsion() != null ? " — " + scan.getLocalisationDistorsion() : "") : "Non").append("\n");
        p.append("Calcifications : ").append(scan.isCalcifications() ? "Oui — " + scan.getTypesCalcifications() +
            " — suspectes : " + scan.getCalcificationsSuspectes() +
            (scan.getLocalisationCalcifications() != null ? " — localisation : " + scan.getLocalisationCalcifications() : "") : "Non").append("\n");

        if (scan.getSignesAssociesMammographie() != null && !scan.getSignesAssociesMammographie().isEmpty()) {
            List<String> locs = scan.getLocalisationsSignesMammographie();
            p.append("Signes associés mammo : ");
            for (int i = 0; i < scan.getSignesAssociesMammographie().size(); i++) {
                p.append(scan.getSignesAssociesMammographie().get(i));
                if (locs != null && i < locs.size() && locs.get(i) != null && !locs.get(i).isBlank())
                    p.append(" (").append(locs.get(i)).append(")");
                p.append("; ");
            }
            p.append("\n");
        }

        if (scan.getMassesMammographie() != null && !scan.getMassesMammographie().isEmpty()) {
            p.append("Masses mammographie (").append(scan.getMassesMammographie().size()).append(") :\n");
            for (int i = 0; i < scan.getMassesMammographie().size(); i++) {
                var m = scan.getMassesMammographie().get(i);
                p.append("  M").append(i + 1).append(" — SEIN: ").append(m.getSein() != null ? m.getSein().toUpperCase() : "?")
                 .append(" | Loc: ").append(m.getLocalisation())
                 .append(" | Forme: ").append(m.getForme())
                 .append(" | Contours: ").append(m.getContours())
                 .append(" | Densité: ").append(m.getDensite()).append("\n");
            }
        }

        p.append("\n=== ÉCHOGRAPHIE ===\n");
        p.append("(Mêmes masses que la mammographie, ne pas compter en double)\n");
        p.append("Échostructure : ").append(scan.getEchostructureMammaire()).append("\n");

        if (scan.getMassesEchostructure() != null && !scan.getMassesEchostructure().isEmpty()) {
            p.append("Masses échographie (").append(scan.getMassesEchostructure().size()).append(") :\n");
            for (int i = 0; i < scan.getMassesEchostructure().size(); i++) {
                var m = scan.getMassesEchostructure().get(i);
                p.append("  M").append(i + 1).append(" — SEIN: ").append(m.getSein() != null ? m.getSein().toUpperCase() : "?")
                 .append(" | Loc: ").append(m.getLocalisation())
                 .append(" | ").append(m.getMesure()).append("mm")
                 .append(" | Forme: ").append(m.getForme())
                 .append(" | Contours: ").append(m.getContours())
                 .append(" | ").append(m.getDensite())
                 .append(" | Orient: ").append(m.getOrientation())
                 .append(" | Comport: ").append(m.getComportementDesFaisceauxUltrasons()).append("\n");
            }
        }

        if (scan.getSignesAssociesEchostructure() != null && !scan.getSignesAssociesEchostructure().isEmpty()) {
            List<String> locs = scan.getLocalisationsSignesEchostructure();
            p.append("Signes associés écho : ");
            for (int i = 0; i < scan.getSignesAssociesEchostructure().size(); i++) {
                p.append(scan.getSignesAssociesEchostructure().get(i));
                if (locs != null && i < locs.size() && locs.get(i) != null && !locs.get(i).isBlank())
                    p.append(" (").append(locs.get(i)).append(")");
                p.append("; ");
            }
            p.append("\n");
        }

        if (scan.getCasSpeciaux() != null && !scan.getCasSpeciaux().isEmpty()) {
            p.append("Cas spéciaux : ");
            for (var cas : scan.getCasSpeciaux()) {
                p.append(cas.getNom());
                if (cas.getLocalisation() != null && !cas.getLocalisation().isBlank())
                    p.append(" (").append(cas.getLocalisation()).append(")");
                p.append("; ");
            }
            p.append("\n");
        }

        // Seins détectés
        boolean hasDroit  = hasSeins(scan, "droit");
        boolean hasGauche = hasSeins(scan, "gauche");
        if (!hasDroit && !hasGauche) { hasDroit = true; hasGauche = true; }

        p.append("\nSEINS AVEC ANOMALIES :\n");
        if (hasDroit)  p.append("- SEIN DROIT : anomalies présentes\n");
        if (hasGauche) p.append("- SEIN GAUCHE : anomalies présentes\n");

        p.append("\nTermine ta réponse OBLIGATOIREMENT par :\n");
        if (hasDroit)  p.append("ACR sein droit : X. Action recommandée : [action]\n");
        if (hasGauche) p.append("ACR sein gauche : X. Action recommandée : [action]\n");

        return p.toString();
    }
}