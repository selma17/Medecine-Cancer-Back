package com.example.goldengymback.serviceImpl;

import com.example.goldengymback.model.*;
import com.example.goldengymback.repository.ClientRepository;
import com.example.goldengymback.repository.MammaryScanRepo;
import com.example.goldengymback.service.MammaryScanService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MammaryScanServiceImpl implements MammaryScanService {

    private final MammaryScanRepo mammaryScanRepository;
    private final ClientRepository clientRepo;
    private static final Logger logger = Logger.getLogger(MammaryScanServiceImpl.class.getName());

    @Autowired
    private BreastCancerService breastCancerService;

    @Autowired
    public MammaryScanServiceImpl(MammaryScanRepo mammaryScanRepository, ClientRepository clientRepo) {
        this.mammaryScanRepository = mammaryScanRepository;
        this.clientRepo = clientRepo;
    }

    @Override
    public String getAcrScoreAndUpdate(Long scanId) {
        MammaryScan scan = mammaryScanRepository.findById(scanId)
                .orElseThrow(() -> new RuntimeException("Scan not found for ID: " + scanId));

        String aiResponse;

        // Si l'ACR ou la conduite sont absents, on reconstruit le prompt
        if (scan.getConclusionIA() == null || scan.getConduiteATenir() == null ||
                scan.getConclusionIA().isBlank() || scan.getConduiteATenir().isBlank()) {

            String prompt = buildClinicalDescription(scan);
            aiResponse = breastCancerService.getDiagnosticFromData(prompt);
            logger.info("AI Response from clinical data: " + aiResponse);

            if (aiResponse == null || aiResponse.trim().isEmpty()) {
                throw new RuntimeException("AI response is empty or null for scan ID: " + scanId);
            }

            String acrScore = extractAcrScore(aiResponse);
            String acrType = extractAcrType(aiResponse);
            String conduiteATenir = aiResponse;

            if (acrScore != null) {
                scan.setConclusionIA(acrScore);
            }
            if (acrType != null) {
                scan.setAcrType(acrType);
            }
            if (conduiteATenir != null) {
                // Appliquer la mise en forme pour la conduite à tenir
                scan.setConduiteATenir(formatConduiteATenir(conduiteATenir));
            }

            mammaryScanRepository.save(scan);
        } else {
            aiResponse = "ACR et conduite à tenir déjà présents.";
        }

        return aiResponse;
    }

    private String buildClinicalDescription(MammaryScan scan) {
        StringBuilder sb = new StringBuilder();
        sb.append("Voici les résultats détaillés d’un examen mammaire d’une patiente suspectée d'une pathologie mammaire :\n");

        // MAMMOGRAPHIE
        if (scan.getDensiteMammaire() != null) {
            sb.append("- Densité mammaire : ").append(scan.getDensiteMammaire()).append("\n");
        }

        sb.append("- Asymétrie : ").append(scan.isAsymetrie() ? "Oui" : "Non").append("\n");
        if (scan.isAsymetrie() && scan.getTypeAsymetrie() != null) {
            sb.append("  - Type d'asymétrie : ").append(scan.getTypeAsymetrie()).append("\n");
        }

        sb.append("- Distorsion architecturale : ").append(scan.isDistorsionArchitecturale() ? "Oui" : "Non").append("\n");
        if (scan.isDistorsionArchitecturale() && scan.getOptionDistorsionArchitecturale() != null) {
            sb.append("  - Détail : ").append(scan.getOptionDistorsionArchitecturale()).append("\n");
        }

        sb.append("- Calcifications : ").append(scan.isCalcifications() ? "Oui" : "Non").append("\n");
        if (scan.isCalcifications()) {
            if (scan.getTypesCalcifications() != null) sb.append("  - Types : ").append(scan.getTypesCalcifications()).append("\n");
            if (scan.getCalcificationsBenignes() != null) sb.append("  - Bénignes : ").append(scan.getCalcificationsBenignes()).append("\n");
            if (scan.getCalcificationsSuspectes() != null) sb.append("  - Suspectes : ").append(scan.getCalcificationsSuspectes()).append("\n");
            if (scan.getDistributionMicrocalcifications() != null) sb.append("  - Distribution : ").append(scan.getDistributionMicrocalcifications()).append("\n");
        }

        if (scan.getSignesAssociesMammographie() != null && !scan.getSignesAssociesMammographie().isEmpty()) {
            sb.append("- Signes associés à la mammographie : ").append(String.join(", ", scan.getSignesAssociesMammographie())).append("\n");
        }

        // MASSES MAMMOGRAPHIE
        if (scan.getMassesMammographie() != null && !scan.getMassesMammographie().isEmpty()) {
            sb.append("- Masses détectées à la mammographie :\n");
            for (MasseMammographie masse : scan.getMassesMammographie()) {
                sb.append("  • Localisation : ").append(masse.getLocalisation()).append("\n");
                sb.append("    - Forme : ").append(masse.getForme()).append("\n");
                sb.append("    - Contours : ").append(masse.getContours()).append("\n");
                sb.append("    - Densité : ").append(masse.getDensite()).append("\n");
            }
        }

        // ÉCHOGRAPHIE
        if (scan.getEchostructureMammaire() != null) {
            sb.append("- Échostructure mammaire : ").append(scan.getEchostructureMammaire()).append("\n");
        }

        if (scan.getSignesAssociesEchostructure() != null && !scan.getSignesAssociesEchostructure().isEmpty()) {
            sb.append("- Signes associés à l'échographie : ").append(String.join(", ", scan.getSignesAssociesEchostructure())).append("\n");
        }

        // MASSES ÉCHOSTRUCTURE
        if (scan.getMassesEchostructure() != null && !scan.getMassesEchostructure().isEmpty()) {
            sb.append("- Masses détectées à l’échographie :\n");
            for (MasseEchostructure masse : scan.getMassesEchostructure()) {
                sb.append("  • Localisation : ").append(masse.getLocalisation()).append("\n");
                sb.append("    - Mesure : ").append(masse.getMesure()).append(" mm\n");
                sb.append("    - Forme : ").append(masse.getForme()).append("\n");
                sb.append("    - Contours : ").append(masse.getContours()).append("\n");
                sb.append("    - Orientation : ").append(masse.getOrientation()).append("\n");
                sb.append("    - Densité : ").append(masse.getDensite()).append("\n");
                sb.append("    - Comportement échographique : ").append(masse.getComportementDesFaisceauxUltrasons()).append("\n");
                if (masse.getCalcifications() != null && !masse.getCalcifications().isBlank()) {
                    sb.append("    - Calcifications : ").append(masse.getCalcifications()).append("\n");
                }
                sb.append("\n");
            }
        }

        // CAS SPÉCIAUX
        if (scan.getCasSpeciaux() != null && !scan.getCasSpeciaux().isEmpty()) {
            sb.append("- Cas spéciaux :\n");
            for (CasSpecial cas : scan.getCasSpeciaux()) {
                sb.append("  • ").append(cas.getNom());
                if (cas.getLocalisation() != null && !cas.getLocalisation().isBlank()) {
                    sb.append(" (localisation : ").append(cas.getLocalisation()).append(")");
                }
                sb.append("\n");
            }
        }

        // INSTRUCTIONS POUR L’IA
        sb.append("\nFournis la conduite à tenir et donne la classification BIRADS de l'ACR 2013.\n");
        sb.append("Rappel final : mammographie et échographie décrivent les MÊMES masses.\n");
        sb.append("Termine toujours par sur une nouvelle ligne : ACR : X. Action recommandée : ...");
        return sb.toString();
    }


    private String formatConduiteATenir(String aiResponse) {
        String[] validActions = {
            "Surveillance", "Biopsie", 
            "Ablation chirurgicale", "Traitement médical"
        };
        for (String action : validActions) {
            if (aiResponse.contains(action)) {
                return action;
            }
        }
        // Pattern regex comme fallback
        Pattern pattern = Pattern.compile(
            "Action\\s+recommand[ée]e\\s*[:\\-]?\\s*([\\w\\s]+?)(?:\\.|\\n|$)",
            Pattern.CASE_INSENSITIVE
        );
        Matcher matcher = pattern.matcher(aiResponse);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return aiResponse;
    }

    private String extractAcrScore(String aiResponse) {
        String acrPattern = "ACR\\s*[:\\-]?\\s*(\\d)";
        Pattern pattern = Pattern.compile(acrPattern, Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(aiResponse);

        if (matcher.find()) {
            logger.info("Found ACR score: " + matcher.group(1));
            return matcher.group(1);
        }

        logger.warning("Could not extract ACR score from: " + aiResponse);
        return null;
    }

    private String extractAcrType(String aiResponse) {
        return null;
    }

    private String extractConduiteATenir(String aiResponse) {
        String actionPattern = "Action\\s+recommand[ée]e\\s*[:\\-]?\\s*(.+?)[\\.\\n]";
        Pattern pattern = Pattern.compile(actionPattern, Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(aiResponse);

        if (matcher.find()) {
            String action = matcher.group(1).trim();
            logger.info("Found action: " + action);
            return action;
        }

        String[] validActions = {"Surveillance", "Biopsie", "Ablation chirurgicale", "Traitement médical"};
        for (String action : validActions) {
            if (aiResponse.contains(action)) {
                logger.info("Found action via direct match: " + action);
                return action;
            }
        }

        logger.warning("Could not extract conduite à tenir from: " + aiResponse);
        return null;
    }

    @Override
    public MammaryScan addMammaryScan(MammaryScan mammaryScan) {
        if (mammaryScan.getClient() != null && mammaryScan.getClient().getId() != null) {
            Long clientId = mammaryScan.getClient().getId();
            Client existingClient = clientRepo.findById(clientId)
                    .orElseThrow(() -> new RuntimeException("Client not found with ID: " + clientId));
            mammaryScan.setClient(existingClient);
        }
        return mammaryScanRepository.save(mammaryScan);
    }

    @Override
    public void delete(Long id) {
        mammaryScanRepository.deleteById(id);
    }

    @Override
    public List<MammaryScan> getAllMammaryScans() {
        return mammaryScanRepository.findAll();
    }

    @Override
    public Optional<MammaryScan> getMammaryScanById(Long id) {
        return mammaryScanRepository.findById(id);
    }

    @Override
    public List<MammaryScan> getByDensiteMammaire(String densiteMammaire) {
        return mammaryScanRepository.findByDensiteMammaire(densiteMammaire);
    }

    @Override
    public List<MammaryScan> getByAsymetrie(Boolean asymetrie) {
        return mammaryScanRepository.findByAsymetrie(asymetrie);
    }
}
