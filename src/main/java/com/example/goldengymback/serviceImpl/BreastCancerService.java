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
        "ACR BI-RADS 2013 ainsi que la conduite à tenir pour chaque sein concerné. " +
        "Les données peuvent inclure uniquement une mammographie, ou une mammographie " +
        "complétée par une échographie. Analyse ce qui est disponible sans imposer " +
        "la présence de l'échographie. " +
        "Si l'échographie est présente, elle complète la mammographie et décrit les MÊMES seins — " +
        "ne jamais compter les masses en double. " +
        "Si un seul sein présente des anomalies, ne classe que ce sein. " +
        "Si les deux seins présentent des anomalies, classe chacun séparément. " +
        "Plusieurs lésions dans un même sein : retenir la classification la plus péjorative.\n\n" +
        "RÈGLE CRITIQUE : chaque sein est classé UNIQUEMENT sur la base de SES PROPRES " +
        "anomalies. Les anomalies d'un sein ne doivent JAMAIS influencer la classification " +
        "de l'autre sein. Si un sein présente des microcalcifications suspectes, une distorsion " +
        "architecturale, des signes associés de malignité ou toute autre anomalie en association " +
        "avec une masse, cela ne concerne QUE ce sein. L'autre sein doit être classé " +
        "indépendamment, uniquement sur ses propres lésions. " +
        "Exemple : sein droit avec masse + microcalcifications suspectes → ACR 4 ou 5. " +
        "Sein gauche avec uniquement une masse bénigne → ACR 2 ou 3, jamais ACR 4.\n\n" +
        "FORMAT OBLIGATOIRE en fin de réponse :\n" +
        "ACR sein droit : X. Action recommandée : [action]\n" +
        "ACR sein gauche : X. Action recommandée : [action]\n\n" +
        "X = 1, 2, 3, 4A, 4B, 4C ou 5\n" +
        "[action] = Surveillance après 6 mois ou Biopsie.\n" ;

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

    // ─── Construction du prompt ────────────────────────────────────────────────
    private String createPrompt(MammaryScan scan) {
        StringBuilder p = new StringBuilder();
        p.append("Analyse cet examen mammaire et fournis la classification BI-RADS ACR 2013.\n");
        p.append("Les champs absents signifient que la donnée n'a pas été recueillie — classe avec ce qui est disponible.\n\n");

        p.append("=== MAMMOGRAPHIE ===\n");
        appendIfNotEmpty(p, "Densité mammaire : ", scan.getDensiteMammaire());

        if (scan.isAsymetrie()) {
            p.append("Asymétrie : Oui");
            if (notEmpty(scan.getTypeAsymetrie()))         p.append(" — ").append(scan.getTypeAsymetrie());
            if (notEmpty(scan.getLocalisationAsymetrie())) p.append(" — localisation : ").append(scan.getLocalisationAsymetrie());
            p.append("\n");
        }

        if (scan.isDistorsionArchitecturale()) {
            p.append("Distorsion architecturale : Oui");
            if (notEmpty(scan.getOptionDistorsionArchitecturale())) p.append(" — ").append(scan.getOptionDistorsionArchitecturale());
            if (notEmpty(scan.getLocalisationDistorsion()))          p.append(" — localisation : ").append(scan.getLocalisationDistorsion());
            p.append("\n");
        }

        if (scan.isCalcifications()) {
            p.append("Calcifications : Oui");
            if (notEmpty(scan.getLocalisationCalcifications()))
                p.append(" — localisation : ").append(scan.getLocalisationCalcifications());
            p.append("\n");
            if (notEmpty(scan.getTypesCalcifications()))
                p.append("  Type : ").append(scan.getTypesCalcifications()).append("\n");
            if (notEmpty(scan.getCalcificationsBenignes()))
                p.append("  Calcifications bénignes : ").append(scan.getCalcificationsBenignes()).append("\n");
            if (notEmpty(scan.getCalcificationsSuspectes()))
                p.append("  Calcifications suspectes : ").append(scan.getCalcificationsSuspectes()).append("\n");
            if (notEmpty(scan.getDistributionMicrocalcifications()))
                p.append("  Distribution : ").append(scan.getDistributionMicrocalcifications()).append("\n");
        }

        if (scan.getSignesAssociesMammographie() != null && !scan.getSignesAssociesMammographie().isEmpty()) {
            p.append("Signes associés (mammographie) :\n");
            List<String> locs = scan.getLocalisationsSignesMammographie();
            for (int i = 0; i < scan.getSignesAssociesMammographie().size(); i++) {
                p.append("  • ").append(scan.getSignesAssociesMammographie().get(i));
                if (locs != null && i < locs.size() && notEmpty(locs.get(i)))
                    p.append(" — localisation : ").append(locs.get(i));
                p.append("\n");
            }
        }

        if (scan.getMassesMammographie() != null && !scan.getMassesMammographie().isEmpty()) {
            p.append("Masses mammographie (").append(scan.getMassesMammographie().size()).append(") :\n");
            for (int i = 0; i < scan.getMassesMammographie().size(); i++) {
                var m = scan.getMassesMammographie().get(i);
                p.append("  M").append(i + 1).append(" :");
                p.append(" SEIN=").append(notEmpty(m.getSein()) ? m.getSein().toUpperCase() : "?");
                if (notEmpty(m.getLocalisation()))  p.append(" | Loc=").append(m.getLocalisation());
                if (notEmpty(m.getForme()))          p.append(" | Forme=").append(m.getForme());
                if (notEmpty(m.getContours()))       p.append(" | Contours=").append(m.getContours());
                if (notEmpty(m.getDensite()))        p.append(" | Densité=").append(m.getDensite());
                p.append("\n");
            }
        }

        p.append("\n=== ÉCHOGRAPHIE ===\n");
        p.append("(Mêmes masses que la mammographie — ne pas compter en double)\n");
        appendIfNotEmpty(p, "Échostructure : ", scan.getEchostructureMammaire());

        if (scan.getMassesEchostructure() != null && !scan.getMassesEchostructure().isEmpty()) {
            p.append("Masses échographie (").append(scan.getMassesEchostructure().size()).append(") :\n");
            for (int i = 0; i < scan.getMassesEchostructure().size(); i++) {
                var m = scan.getMassesEchostructure().get(i);
                p.append("  M").append(i + 1).append(" :");
                p.append(" SEIN=").append(notEmpty(m.getSein()) ? m.getSein().toUpperCase() : "?");
                if (notEmpty(m.getLocalisation()))                     p.append(" | Loc=").append(m.getLocalisation());
                if (notEmpty(m.getMesure()))                            p.append(" | ").append(m.getMesure()).append("mm");
                if (notEmpty(m.getDistanceCentre()))                    p.append(" | Dist.mamelon=").append(m.getDistanceCentre()).append("mm");
                if (notEmpty(m.getForme()))                             p.append(" | Forme=").append(m.getForme());
                if (notEmpty(m.getContours()))                          p.append(" | Contours=").append(m.getContours());
                if (notEmpty(m.getDensite()))                           p.append(" | Écho=").append(m.getDensite());
                if (notEmpty(m.getOrientation()))                       p.append(" | Orient=").append(m.getOrientation());
                if (notEmpty(m.getComportementDesFaisceauxUltrasons())) p.append(" | Comport=").append(m.getComportementDesFaisceauxUltrasons());
                if (notEmpty(m.getCalcifications()))                    p.append(" | Calcif=").append(m.getCalcifications());
                p.append("\n");
            }
        }

        if (scan.getSignesAssociesEchostructure() != null && !scan.getSignesAssociesEchostructure().isEmpty()) {
            p.append("Signes associés (échographie) :\n");
            List<String> locs = scan.getLocalisationsSignesEchostructure();
            for (int i = 0; i < scan.getSignesAssociesEchostructure().size(); i++) {
                p.append("  • ").append(scan.getSignesAssociesEchostructure().get(i));
                if (locs != null && i < locs.size() && notEmpty(locs.get(i)))
                    p.append(" — localisation : ").append(locs.get(i));
                p.append("\n");
            }
        }

        if (scan.getCasSpeciaux() != null && !scan.getCasSpeciaux().isEmpty()) {
            p.append("Cas spéciaux :\n");
            for (var cas : scan.getCasSpeciaux()) {
                p.append("  • ").append(cas.getNom());
                if (notEmpty(cas.getLocalisation()))
                    p.append(" — localisation : ").append(cas.getLocalisation());
                p.append("\n");
            }
        }

        boolean hasDroit  = hasSeins(scan, "droit");
        boolean hasGauche = hasSeins(scan, "gauche");
        if (!hasDroit && !hasGauche) { hasDroit = true; hasGauche = true; }

        p.append("\nSEINS AVEC ANOMALIES :\n");
        if (hasDroit)  p.append("- SEIN DROIT\n");
        if (hasGauche) p.append("- SEIN GAUCHE\n");

        p.append("\nTermine ta réponse OBLIGATOIREMENT par :\n");
        if (hasDroit)  p.append("ACR sein droit : X. Action recommandée : [action]\n");
        if (hasGauche) p.append("ACR sein gauche : X. Action recommandée : [action]\n");

        return p.toString();
    }
}