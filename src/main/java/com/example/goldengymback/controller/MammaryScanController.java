package com.example.goldengymback.controller;

import com.example.goldengymback.model.MammaryScan;
import com.example.goldengymback.service.MammaryScanService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.HashMap;

@RestController
@RequestMapping("/api/mammary-scan")
public class MammaryScanController {

    private final MammaryScanService mammaryScanService;

    @Autowired
    public MammaryScanController(MammaryScanService mammaryScanService) {
        this.mammaryScanService = mammaryScanService;
    }
    @PostMapping("/add")
        public ResponseEntity<?> addMammaryScan(@RequestBody MammaryScan mammaryScan) {
            try {
                MammaryScan createdScan = mammaryScanService.addMammaryScan(mammaryScan);
                return new ResponseEntity<>(createdScan, HttpStatus.CREATED);
            } catch (Exception e) {
                e.printStackTrace();
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("ERREUR: " + e.getMessage() + " | Cause: " + 
                            (e.getCause() != null ? e.getCause().getMessage() : "null"));
            }
        }
    // Endpoint to create and submit a MammaryScan form
    @PostMapping("/submit")
    public ResponseEntity<MammaryScan> submitMammaryScan(@RequestBody MammaryScan mammaryScan) {
        MammaryScan savedScan = mammaryScanService.addMammaryScan(mammaryScan);
        return new ResponseEntity<>(savedScan, HttpStatus.CREATED);
    }

    // Endpoint to get ACR score and action
    @GetMapping("/acr/{scanId}")
    public ResponseEntity<String> getAcrScore(@PathVariable Long scanId) {
        try {
            String score = mammaryScanService.getAcrScoreAndUpdate(scanId);
            System.out.println("hhhhhh"+score);
            
            // Récupérer le scan pour obtenir le type ACR
            Optional<MammaryScan> scanOpt = mammaryScanService.getMammaryScanById(scanId);
            if (scanOpt.isPresent()) {
                MammaryScan scan = scanOpt.get();
                String acrType = scan.getAcrType();
                if (acrType != null && !acrType.isEmpty()) {
                    return ResponseEntity.ok("ACR : " + scan.getConclusionIA() + " (Type " + acrType + "). " + scan.getConduiteATenir());
                }
            }
            
            return ResponseEntity.ok(score);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error: " + e.getMessage());
        }
    }

    // Endpoint to get ACR score with type
    @GetMapping("/acr-with-type/{scanId}")
    public ResponseEntity<Map<String, Object>> getAcrScoreWithType(@PathVariable Long scanId) {
        try {
            Optional<MammaryScan> scanOpt = mammaryScanService.getMammaryScanById(scanId);
            if (scanOpt.isPresent()) {
                MammaryScan scan = scanOpt.get();
                Map<String, Object> response = new HashMap<>();
                response.put("acrScore", scan.getConclusionIA());
                response.put("acrType", scan.getAcrType());
                response.put("conduiteATenir", scan.getConduiteATenir());
                response.put("fullAcr", scan.getConclusionIA() + " (Type " + scan.getAcrType() + ")");
                return ResponseEntity.ok(response);
            } else {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Scan not found"));
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }
    @GetMapping("/all")
    public ResponseEntity<List<MammaryScan>> getAllMammaryScans() {
        List<MammaryScan> scans = mammaryScanService.getAllMammaryScans();
        // Le type ACR sera automatiquement inclus dans la réponse JSON grâce au champ acrType du modèle
        return new ResponseEntity<>(scans, HttpStatus.OK);
    }
    @GetMapping("/{id}")
    public ResponseEntity<MammaryScan> getMammaryScanById(@PathVariable Long id) {
        Optional<MammaryScan> scan = mammaryScanService.getMammaryScanById(id);
        return scan.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }
    @GetMapping("/by-densite/{densite}")
    public ResponseEntity<List<MammaryScan>> getMammaryScansByDensiteMammaire(@PathVariable String densite) {
        List<MammaryScan> scans = mammaryScanService.getByDensiteMammaire(densite);
        return new ResponseEntity<>(scans, HttpStatus.OK);
    }
    @GetMapping("/by-asymetrie/{asymetrie}")
    public ResponseEntity<List<MammaryScan>> getMammaryScansByAsymetrie(@PathVariable Boolean asymetrie) {
        List<MammaryScan> scans = mammaryScanService.getByAsymetrie(asymetrie);
        return new ResponseEntity<>(scans, HttpStatus.OK);
    }
    @DeleteMapping("/delete/{id}")
    public ResponseEntity<Void> deleteMammaryScan(@PathVariable Long id) {
        mammaryScanService.delete(id);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
    @DeleteMapping("/delete-all")
    public ResponseEntity<Void> deleteAllScans() {
        mammaryScanService.getAllMammaryScans()
            .forEach(scan -> mammaryScanService.delete(scan.getId()));
        return ResponseEntity.noContent().build();
    }
}
