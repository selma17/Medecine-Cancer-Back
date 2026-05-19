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
            Map.of("role", "system", "content",
                "Tu es un radiologue expert en imagerie mammaire spécialisé dans la classification BI-RADS ACR 2013. " +
                "Tu analyses les données d'examens mammographiques et échographiques. " +
                "IMPORTANT : La mammographie et l'échographie examinent les MÊMES seins. " +
                "Les masses décrites en mammographie et en échographie sont les MÊMES masses vues sous deux modalités différentes. Ne jamais les compter en double. " +
                "Réponds toujours en français. " +
                "CLASSIFICATION BI-RADS ACR 2013 STRICTE — respecte exactement ces définitions :\n" +
                "- ACR 1 : Examen normal, aucune anomalie — Surveillance habituelle\n" +
                "- ACR 2 : Anomalie bénigne certaine (kyste simple, ganglion, calcifications bénignes typiques) — Surveillance habituelle\n" +
                "- ACR 3 : Anomalie probablement bénigne (probabilité de malignité < 2%) — Surveillance à court terme 6 mois\n" +
                "- ACR 4 : Anomalie suspecte (probabilité de malignité 2-95%) — Biopsie recommandée\n" +
                "  * ACR 4A : Faible suspicion de malignité (2-10%) — Biopsie\n" +
                "  * ACR 4B : Suspicion intermédiaire (10-50%) — Biopsie\n" +
                "  * ACR 4C : Suspicion modérément élevée (50-95%) — Biopsie\n" +
                "- ACR 5 : Anomalie hautement suspecte de malignité (probabilité > 95%) — Biopsie indispensable\n" +
                "RÈGLE ABSOLUE : Une masse à contours circonscrits et forme ovale = ACR 3 minimum. " +
                "Une masse à contours spiculés ou irréguliers = ACR 4 minimum. " +
                "Des calcifications suspectes = ACR 4 minimum. " +
                "FORMAT OBLIGATOIRE en fin de réponse (dernière ligne) : 'ACR : X. Action recommandée : [action]' " +
                "où X est entre 1 et 5 (si ACR 4, préciser le sous-type : 4A, 4B ou 4C), " +
                "et [action] est exactement l'une de : Surveillance, Biopsie, Ablation chirurgicale, Traitement médical. " +
                "NE PAS inclure de Type (A, B, C) dans la réponse."
            ),
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
                    return (String) message.get("content");
                }
            }
            throw new RuntimeException("Réponse invalide de l'API OpenRouter");
        } catch (Exception e) {
            throw new RuntimeException("Erreur appel API OpenRouter: " + e.getMessage());
        }
    }

    private void updateScanWithAiResponse(String aiResponse, MammaryScan scan) {
        Pattern pattern = Pattern.compile(
            "ACR\\s*[:\\-]?\\s*(\\d)\\s*\\(Type\\s*([ABC])\\).*?Action recommandée\\s*[:\\-]?\\s*(.+)",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
        );
        Matcher matcher = pattern.matcher(aiResponse);

        if (matcher.find()) {
            String acrScore = matcher.group(1).trim();
            String acrType = matcher.group(2).trim();
            String conduite = matcher.group(3).trim();

            List<String> validConduites = List.of(
                "Surveillance", "Biopsie", "Ablation chirurgicale", "Traitement médical"
            );

            if (!validConduites.contains(conduite)) {
                for (String valid : validConduites) {
                    if (conduite.contains(valid)) {
                        conduite = valid;
                        break;
                    }
                }
            }

            scan.setConclusionIA(acrScore);
            scan.setAcrType(acrType);
            scan.setConduiteATenir(conduite);
            mammaryScanRepository.save(scan);
        } else {
            throw new RuntimeException("Format de réponse IA invalide. Réponse: " + aiResponse);
        }
    }

    private String createPrompt(MammaryScan scan) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Analyse cet examen mammaire complet :\n\n");

        prompt.append("=== MAMMOGRAPHIE ===\n");
        prompt.append("- Densité mammaire : ").append(scan.getDensiteMammaire()).append("\n");
        prompt.append("- Asymétrie : ").append(scan.isAsymetrie() ? "Oui" : "Non").append("\n");
        if (scan.isAsymetrie()) {
            prompt.append("  - Type : ").append(scan.getTypeAsymetrie()).append("\n");
        }
        prompt.append("- Distorsion architecturale : ").append(scan.isDistorsionArchitecturale() ? "Oui" : "Non").append("\n");
        prompt.append("- Calcifications : ").append(scan.isCalcifications() ? "Oui" : "Non").append("\n");
        if (scan.isCalcifications()) {
            prompt.append("  - Types : ").append(scan.getTypesCalcifications()).append("\n");
            prompt.append("  - Suspectes : ").append(scan.getCalcificationsSuspectes()).append("\n");
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
                prompt.append("    Comportement : ")
                      .append(m.getComportementDesFaisceauxUltrasons()).append("\n");
            }
        }

        if (scan.getSignesAssociesEchostructure() != null && !scan.getSignesAssociesEchostructure().isEmpty()) {
            prompt.append("- Signes associés échographie : ")
                  .append(String.join(", ", scan.getSignesAssociesEchostructure())).append("\n");
        }

        if (scan.getCasSpeciaux() != null && !scan.getCasSpeciaux().isEmpty()) {
            prompt.append("- Cas spéciaux : \n");
            for (var cas : scan.getCasSpeciaux()) {
                prompt.append("  • ").append(cas.getNom());
                if (cas.getLocalisation() != null && !cas.getLocalisation().isBlank()) {
                    prompt.append(" (localisation : ").append(cas.getLocalisation()).append(")");
                }
                prompt.append("\n");
            }
        }

        prompt.append("\nFournis la conduite à tenir et donne la classification BIRADS de l'ACR 2013.\n");
        prompt.append("Rappel final : mammographie et échographie décrivent les MÊMES masses.\n");
        prompt.append("Termine par sur une nouvelle ligne : ACR : X. Action recommandée : ...");
        return prompt.toString();
    }
}