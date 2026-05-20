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
    private static final String OPENROUTER_MODEL = "deepseek/deepseek-v4-flash:free";

    @Autowired
    private MammaryScanRepo mammaryScanRepository;

    // ─── Prompt système ────────────────────────────────────────────────────────
    private static final String SYSTEM_PROMPT =
        "Tu es un radiologue expert en imagerie mammaire spécialisé dans la classification BI-RADS ACR 2013. " +
        "Réponds toujours en français.\n\n" +
        "RÈGLE FONDAMENTALE : La mammographie et l'échographie examinent les MÊMES seins. " +
        "Les masses décrites dans les deux modalités sont les MÊMES masses. Ne jamais les compter en double.\n\n" +
        "CLASSIFICATION BI-RADS ACR 2013 STRICTE :\n" +
        "- ACR 1 : Examen normal — Surveillance\n" +
        "- ACR 2 : Anomalie bénigne certaine — Surveillance\n" +
        "- ACR 3 : Probablement bénigne (malignité < 2%) — Surveillance\n" +
        "- ACR 4A : Faible suspicion (2-10%) — Biopsie\n" +
        "- ACR 4B : Suspicion intermédiaire (10-50%) — Biopsie\n" +
        "- ACR 4C : Suspicion élevée (50-95%) — Biopsie\n" +
        "- ACR 5 : Hautement suspect (> 95%) — Biopsie\n\n" +
        "RÈGLES STRICTES :\n" +
        "- Masse ovale + contours circonscrits = ACR 3 minimum\n" +
        "- Masse irrégulière + contours spiculés = ACR 4C minimum\n" +
        "- Calcifications suspectes = ACR 4 minimum\n" +
        "- Plusieurs lésions dans un sein : retenir la plus péjorative\n\n" +
        "INSTRUCTION ABSOLUE — FORMAT DE RÉPONSE :\n" +
        "Tu DOIS terminer ta réponse par EXACTEMENT ces deux lignes, sans exception :\n" +
        "ACR sein droit : X. Action recommandée : [action]\n" +
        "ACR sein gauche : X. Action recommandée : [action]\n\n" +
        "Où X est le score ACR (1, 2, 3, 4A, 4B, 4C ou 5).\n" +
        "Et [action] est OBLIGATOIREMENT l'un de ces mots exacts : Surveillance, Biopsie, Ablation chirurgicale, Traitement médical.\n\n" +
        "EXEMPLE de fin de réponse correcte :\n" +
        "ACR sein droit : 3. Action recommandée : Surveillance\n" +
        "ACR sein gauche : 4C. Action recommandée : Biopsie\n\n" +
        "NE PAS écrire ACR sans préciser le sein. Toujours deux lignes séparées, une par sein.";

    // ─── Point d'entrée principal ──────────────────────────────────────────────
    @Override
    public String getAcrScore(Long scanId) {
        MammaryScan scan = mammaryScanRepository.findById(scanId)
                .orElseThrow(() -> new RuntimeException("Scan not found for ID: " + scanId));

        String prompt = createPrompt(scan);
        String aiResponse = callOpenRouterApi(prompt);
        updateScanWithAiResponse(aiResponse, scan);

        return aiResponse;
    }

    @Override
    public String getDiagnosticFromData(String description) {
        String aiResponse = callOpenRouterApi(description);

        if (aiResponse == null || aiResponse.trim().isEmpty()) {
            throw new RuntimeException("Réponse IA vide ou invalide.");
        }

        return aiResponse;
    }

    // ─── Appel API OpenRouter ──────────────────────────────────────────────────
    private String callOpenRouterApi(String prompt) {
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + openrouterApiKey);
        headers.set("Content-Type", "application/json");
        headers.set("HTTP-Referer", "https://srv-d7dqlh9j2pic73fplqa0.onrender.com");
        headers.set("X-Title", "CancerIA");

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", OPENROUTER_MODEL);
        requestBody.put("messages", List.of(
            Map.of("role", "system", "content", SYSTEM_PROMPT),
            Map.of("role", "user", "content", prompt)
        ));
        requestBody.put("max_tokens", 1500);
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
                    return (String) message.get("content");
                }
            }
            throw new RuntimeException("Réponse invalide de l'API OpenRouter");
        } catch (Exception e) {
            throw new RuntimeException("Erreur appel API OpenRouter: " + e.getMessage());
        }
    }

    // ─── Parsing de la réponse IA et mise à jour du scan ──────────────────────
    private void updateScanWithAiResponse(String aiResponse, MammaryScan scan) {

        Pattern droitPattern = Pattern.compile(
            "ACR\\s+sein\\s+droit\\s*[:\\-]?\\s*([0-9][ABC]?).*?Action\\s+recommand[ée]+e?\\s*[:\\-]?\\s*(.+?)(?=\\n|ACR\\s+sein\\s+gauche|$)",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
        );
        Pattern gauchePattern = Pattern.compile(
            "ACR\\s+sein\\s+gauche\\s*[:\\-]?\\s*([0-9][ABC]?).*?Action\\s+recommand[ée]+e?\\s*[:\\-]?\\s*(.+?)(?=\\n|$)",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
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

        // Fallback : l'IA n'a pas respecté le format par sein → on distribue le score global
        if (acrDroit == null && acrGauche == null) {
            Pattern fallback = Pattern.compile(
                "ACR\\s*[:\\-]?\\s*(\\d[ABC]?).*?Action\\s+recommand[ée]+e?\\s*[:\\-]?\\s*(.+)",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE
            );
            Matcher fm = fallback.matcher(aiResponse);
            if (fm.find()) {
                String acrGlobal      = fm.group(1).trim();
                String conduiteGlobal = normalizeConduite(fm.group(2).trim(), validConduites);
                scan.setConclusionIA(acrGlobal);
                scan.setConduiteATenir(conduiteGlobal);
                scan.setFullAiResponse(aiResponse);

                // Distribuer le score global aux seins qui ont des masses
                boolean hasDroitFallback  = scan.getMassesMammographie().stream()
                    .anyMatch(m -> m.getSein() != null && m.getSein().toLowerCase().contains("droit"))
                    || scan.getMassesEchostructure().stream()
                    .anyMatch(m -> m.getSein() != null && m.getSein().toLowerCase().contains("droit"));
                boolean hasGaucheFallback = scan.getMassesMammographie().stream()
                    .anyMatch(m -> m.getSein() != null && m.getSein().toLowerCase().contains("gauche"))
                    || scan.getMassesEchostructure().stream()
                    .anyMatch(m -> m.getSein() != null && m.getSein().toLowerCase().contains("gauche"));

                // Si aucun sein précisé dans les masses, distribuer aux deux
                if (!hasDroitFallback && !hasGaucheFallback) {
                    hasDroitFallback  = true;
                    hasGaucheFallback = true;
                }

                if (hasDroitFallback)  { scan.setAcrDroit(acrGlobal);  scan.setRecommandationDroit(conduiteGlobal);  }
                if (hasGaucheFallback) { scan.setAcrGauche(acrGlobal); scan.setRecommandationGauche(conduiteGlobal); }

                mammaryScanRepository.save(scan);
                return;
            }
            throw new RuntimeException("Format de réponse IA invalide. Réponse: " + aiResponse);
        }

        // Score global = le plus péjoratif des deux seins
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

    // ─── Utilitaires ──────────────────────────────────────────────────────────

    private String normalizeConduite(String raw, List<String> validConduites) {
        if (raw == null) return "Surveillance";
        String cleaned = raw.split("\\.")[0].trim();
        for (String valid : validConduites) {
            if (cleaned.equalsIgnoreCase(valid)) return valid;
        }
        for (String valid : validConduites) {
            if (cleaned.contains(valid)) return valid;
        }
        return "Surveillance";
    }

    private String computeGlobalAcr(String acrDroit, String acrGauche) {
        if (acrDroit == null)  return acrGauche != null ? acrGauche : "1";
        if (acrGauche == null) return acrDroit;

        int numDroit  = Integer.parseInt(String.valueOf(acrDroit.charAt(0)));
        int numGauche = Integer.parseInt(String.valueOf(acrGauche.charAt(0)));

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
            "Surveillance",          1,
            "Traitement médical",    2,
            "Ablation chirurgicale", 3,
            "Biopsie",               4
        );

        int p1 = priority.getOrDefault(c1, 1);
        int p2 = priority.getOrDefault(c2, 1);
        return p1 >= p2 ? c1 : c2;
    }

    // ─── Construction du prompt utilisateur ───────────────────────────────────
    private String createPrompt(MammaryScan scan) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Analyse cet examen mammaire complet :\n\n");

        // ── Mammographie ──────────────────────────────────────────────────────
        prompt.append("=== MAMMOGRAPHIE ===\n");
        prompt.append("- Densité mammaire : ").append(scan.getDensiteMammaire()).append("\n");

        prompt.append("- Asymétrie : ").append(scan.isAsymetrie() ? "Oui" : "Non").append("\n");
        if (scan.isAsymetrie()) {
            prompt.append("  - Type : ").append(scan.getTypeAsymetrie()).append("\n");
            if (scan.getLocalisationAsymetrie() != null && !scan.getLocalisationAsymetrie().isBlank()) {
                prompt.append("  - Localisation : ").append(scan.getLocalisationAsymetrie()).append("\n");
            }
        }

        prompt.append("- Distorsion architecturale : ").append(scan.isDistorsionArchitecturale() ? "Oui" : "Non").append("\n");
        if (scan.isDistorsionArchitecturale()) {
            if (scan.getOptionDistorsionArchitecturale() != null && !scan.getOptionDistorsionArchitecturale().isBlank()) {
                prompt.append("  - Option : ").append(scan.getOptionDistorsionArchitecturale()).append("\n");
            }
            if (scan.getLocalisationDistorsion() != null && !scan.getLocalisationDistorsion().isBlank()) {
                prompt.append("  - Localisation : ").append(scan.getLocalisationDistorsion()).append("\n");
            }
        }

        prompt.append("- Calcifications : ").append(scan.isCalcifications() ? "Oui" : "Non").append("\n");
        if (scan.isCalcifications()) {
            prompt.append("  - Types : ").append(scan.getTypesCalcifications()).append("\n");
            prompt.append("  - Suspectes : ").append(scan.getCalcificationsSuspectes()).append("\n");
            if (scan.getLocalisationCalcifications() != null && !scan.getLocalisationCalcifications().isBlank()) {
                prompt.append("  - Localisation : ").append(scan.getLocalisationCalcifications()).append("\n");
            }
        }

        // Signes associés mammographie — List<String> avec localisations parallèles
        if (scan.getSignesAssociesMammographie() != null && !scan.getSignesAssociesMammographie().isEmpty()) {
            prompt.append("- Signes associés (mammographie) :\n");
            List<String> locsMammo = scan.getLocalisationsSignesMammographie();
            for (int i = 0; i < scan.getSignesAssociesMammographie().size(); i++) {
                String signe = scan.getSignesAssociesMammographie().get(i);
                prompt.append("  • ").append(signe);
                if (locsMammo != null && i < locsMammo.size()
                        && locsMammo.get(i) != null && !locsMammo.get(i).isBlank()) {
                    prompt.append(" (localisation : ").append(locsMammo.get(i)).append(")");
                }
                prompt.append("\n");
            }
        }

        if (scan.getMassesMammographie() != null && !scan.getMassesMammographie().isEmpty()) {
            prompt.append("- Nombre de masses à la mammographie : ")
                  .append(scan.getMassesMammographie().size()).append("\n");
            for (int i = 0; i < scan.getMassesMammographie().size(); i++) {
                var m = scan.getMassesMammographie().get(i);
                prompt.append("  Masse ").append(i + 1).append(" (mammographie) :\n");
                prompt.append("    Localisation : ").append(m.getLocalisation()).append("\n");
                prompt.append("    Forme : ").append(m.getForme()).append("\n");
                prompt.append("    Contours : ").append(m.getContours()).append("\n");
                prompt.append("    Densité : ").append(m.getDensite()).append("\n");
            }
        }

        // ── Échographie ───────────────────────────────────────────────────────
        prompt.append("\n=== ÉCHOGRAPHIE ===\n");
        prompt.append("RAPPEL : Ces masses sont les MÊMES que celles de la mammographie,\n");
        prompt.append("décrites sous une modalité différente. Ne pas les compter en double.\n");
        prompt.append("- Échostructure : ").append(scan.getEchostructureMammaire()).append("\n");

        if (scan.getMassesEchostructure() != null && !scan.getMassesEchostructure().isEmpty()) {
            prompt.append("- Caractéristiques échographiques des masses :\n");
            for (int i = 0; i < scan.getMassesEchostructure().size(); i++) {
                var m = scan.getMassesEchostructure().get(i);
                prompt.append("  Masse ").append(i + 1).append(" (échographie) :\n");
                prompt.append("    Localisation : ").append(m.getLocalisation()).append("\n");
                prompt.append("    Mesure : ").append(m.getMesure()).append(" mm\n");
                prompt.append("    Forme : ").append(m.getForme()).append("\n");
                prompt.append("    Contours : ").append(m.getContours()).append("\n");
                prompt.append("    Densité : ").append(m.getDensite()).append("\n");
                prompt.append("    Orientation : ").append(m.getOrientation()).append("\n");
                prompt.append("    Comportement : ").append(m.getComportementDesFaisceauxUltrasons()).append("\n");
            }
        }

        // Signes associés échographie — List<String> avec localisations parallèles
        if (scan.getSignesAssociesEchostructure() != null && !scan.getSignesAssociesEchostructure().isEmpty()) {
            prompt.append("- Signes associés (échographie) :\n");
            List<String> locsEcho = scan.getLocalisationsSignesEchostructure();
            for (int i = 0; i < scan.getSignesAssociesEchostructure().size(); i++) {
                String signe = scan.getSignesAssociesEchostructure().get(i);
                prompt.append("  • ").append(signe);
                if (locsEcho != null && i < locsEcho.size()
                        && locsEcho.get(i) != null && !locsEcho.get(i).isBlank()) {
                    prompt.append(" (localisation : ").append(locsEcho.get(i)).append(")");
                }
                prompt.append("\n");
            }
        }

        if (scan.getCasSpeciaux() != null && !scan.getCasSpeciaux().isEmpty()) {
            prompt.append("- Cas spéciaux :\n");
            for (var cas : scan.getCasSpeciaux()) {
                prompt.append("  • ").append(cas.getNom());
                if (cas.getLocalisation() != null && !cas.getLocalisation().isBlank()) {
                    prompt.append(" (localisation : ").append(cas.getLocalisation()).append(")");
                }
                prompt.append("\n");
            }
        }

        // Détecter les seins concernés
        boolean hasDroit = false;
        boolean hasGauche = false;
        if (scan.getMassesMammographie() != null) {
            for (var m : scan.getMassesMammographie()) {
                if (m.getSein() != null) {
                    if (m.getSein().toLowerCase().contains("droit")) hasDroit = true;
                    if (m.getSein().toLowerCase().contains("gauche")) hasGauche = true;
                }
            }
        }
        if (scan.getMassesEchostructure() != null) {
            for (var m : scan.getMassesEchostructure()) {
                if (m.getSein() != null) {
                    if (m.getSein().toLowerCase().contains("droit")) hasDroit = true;
                    if (m.getSein().toLowerCase().contains("gauche")) hasGauche = true;
                }
            }
        }
        // Si aucun sein précisé, on considère les deux
        if (!hasDroit && !hasGauche) { hasDroit = true; hasGauche = true; }

        prompt.append("\nFournis l'analyse et la classification BI-RADS ACR 2013.\n");
        prompt.append("Rappel final : mammographie et échographie décrivent les MÊMES masses.\n");
        prompt.append("\nSEINS CONCERNÉS PAR CET EXAMEN :\n");
        if (hasDroit)  prompt.append("- Sein DROIT : présence de masses/anomalies\n");
        if (hasGauche) prompt.append("- Sein GAUCHE : présence de masses/anomalies\n");
        prompt.append("\nTermine obligatoirement par (une ligne par sein concerné) :\n");
        if (hasDroit)  prompt.append("ACR sein droit : X. Action recommandée : [action]\n");
        if (hasGauche) prompt.append("ACR sein gauche : X. Action recommandée : [action]\n");
        prompt.append("Si un sein contient plusieurs masses, retenir la plus péjorative pour ce sein.");

        return prompt.toString();
    }
}