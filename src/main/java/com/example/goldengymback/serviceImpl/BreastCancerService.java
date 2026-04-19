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

    @Value("${groq.api.key}")
    private String groqApiKey;

    private static final String GROQ_API_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final String GROQ_MODEL = "llama3-70b-8192";

    @Autowired
    private MammaryScanRepo mammaryScanRepository;

    @Override
    public String getAcrScore(Long scanId) {
        MammaryScan scan = mammaryScanRepository.findById(scanId)
                .orElseThrow(() -> new RuntimeException("Scan not found for ID: " + scanId));

        String prompt = createPrompt(scan);
        String aiResponse = callGroqApi(prompt);
        updateScanWithAiResponse(aiResponse, scan);

        return aiResponse;
    }

    @Override
    public String getDiagnosticFromData(String description) {
        String aiResponse = callGroqApi(description);

        if (aiResponse == null || aiResponse.trim().isEmpty()) {
            throw new RuntimeException("Réponse IA vide ou invalide.");
        }

        return aiResponse;
    }

    private String callGroqApi(String prompt) {
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + groqApiKey);
        headers.set("Content-Type", "application/json");

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", GROQ_MODEL);
        requestBody.put("messages", List.of(
            Map.of("role", "system", "content",
                "Tu es un radiologue expert en imagerie mammaire. " +
                "Tu analyses les données d'examens mammographiques et échographiques " +
                "et tu fournis un score ACR et une conduite à tenir. " +
                "Réponds toujours en français. " +
                "Format obligatoire en fin de réponse : 'ACR : X (Type Y). Action recommandée : [action]' " +
                "où X est entre 1 et 5, Y est A, B ou C, " +
                "et [action] est exactement l'une de : Surveillance, Biopsie, Ablation chirurgicale, Traitement médical."
            ),
            Map.of("role", "user", "content", prompt)
        ));
        requestBody.put("max_tokens", 1000);
        requestBody.put("temperature", 0);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                GROQ_API_URL, HttpMethod.POST, entity, Map.class
            );
            Map<String, Object> responseBody = response.getBody();

            if (responseBody != null && responseBody.containsKey("choices")) {
                List<Map<String, Object>> choices = (List<Map<String, Object>>) responseBody.get("choices");
                if (!choices.isEmpty()) {
                    Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                    return (String) message.get("content");
                }
            }
            throw new RuntimeException("Réponse invalide de l'API Groq");
        } catch (Exception e) {
            throw new RuntimeException("Erreur appel API Groq: " + e.getMessage());
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
                // Chercher une conduite valide dans le texte
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
        prompt.append("Analyse cet examen mammaire :\n");
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
        prompt.append("- Échostructure : ").append(scan.getEchostructureMammaire()).append("\n");
        prompt.append("\nFournis le score ACR et la conduite à tenir.");
        return prompt.toString();
    }
}