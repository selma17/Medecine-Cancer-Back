package com.example.goldengymback.serviceImpl;

import com.example.goldengymback.model.*;
import com.example.goldengymback.repository.ClientRepository;
import com.example.goldengymback.repository.MammaryScanRepo;
import com.example.goldengymback.service.MammaryScanService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class MammaryScanServiceImpl implements MammaryScanService {

    private final MammaryScanRepo mammaryScanRepository;
    private final ClientRepository clientRepo;

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

        // Si déjà analysé, ne pas relancer
        if (scan.getConclusionIA() != null && !scan.getConclusionIA().isBlank()
                && scan.getConduiteATenir() != null && !scan.getConduiteATenir().isBlank()) {
            return "ACR et conduite à tenir déjà présents.";
        }

        // Déléguer entièrement à BreastCancerService qui gère
        // le prompt, l'appel IA, le parsing par sein et la sauvegarde
        return breastCancerService.getAcrScore(scanId);
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