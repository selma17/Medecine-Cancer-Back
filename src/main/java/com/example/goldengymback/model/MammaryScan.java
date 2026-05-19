package com.example.goldengymback.model;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@ToString
@Entity
public class MammaryScan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // MAMMOGRAPHIE
    private String densiteMammaire;

    private boolean asymetrie;
    private String typeAsymetrie;
    private String localisationAsymetrie;        // NOUVEAU

    private boolean distorsionArchitecturale;
    private String optionDistorsionArchitecturale;
    private String localisationDistorsion;        // NOUVEAU

    private boolean calcifications;
    private String typesCalcifications;
    private String calcificationsBenignes;
    private String calcificationsSuspectes;
    private String distributionMicrocalcifications;
    private String localisationCalcifications;    // NOUVEAU

    private boolean isSignesAssociesRequired;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "signes_associes_mammographie", joinColumns = @JoinColumn(name = "mammary_scan_id"))
    @Column(name = "signe")
    private List<String> signesAssociesMammographie;

    // Localisations des signes associés mammographie (même index que la liste ci-dessus)
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "signes_associes_mammographie_localisation", joinColumns = @JoinColumn(name = "mammary_scan_id"))
    @Column(name = "localisation")
    private List<String> localisationsSignesMammographie;  // NOUVEAU

    // ÉCHOGRAPHIE MAMMAIRE
    private String echostructureMammaire;

    private boolean isSignesAssociesEchostructureRequired;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "signes_associes_echostructure", joinColumns = @JoinColumn(name = "mammary_scan_id"))
    @Column(name = "signe")
    private List<String> signesAssociesEchostructure;

    // Localisations des signes associés échographie (même index que la liste ci-dessus)
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "signes_associes_echostructure_localisation", joinColumns = @JoinColumn(name = "mammary_scan_id"))
    @Column(name = "localisation")
    private List<String> localisationsSignesEchostructure; // NOUVEAU

    private boolean isCasSpeciauxRequired;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "cas_speciaux", joinColumns = @JoinColumn(name = "mammary_scan_id"))
    private List<CasSpecial> casSpeciaux;

    private String conclusionRadiologue;
    private String conclusionIA;
    private String acrType;

    @Column(name = "conduiteatenir", columnDefinition = "TEXT")
    private String conduiteATenir;

    // Résultats IA par sein — NOUVEAUX
    private String acrDroit;
    private String acrGauche;

    @Column(columnDefinition = "TEXT")
    private String recommandationDroit;

    @Column(columnDefinition = "TEXT")
    private String recommandationGauche;

    @Column(columnDefinition = "TEXT")
    private String fullAiResponse;

    @ManyToOne
    @JoinColumn(name = "client_id")
    @JsonIgnoreProperties({"mammaryScans", "medecin", "password"})
    private Client client;

    @OneToMany(mappedBy = "mammaryScan", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JsonManagedReference
    private List<MasseMammographie> massesMammographie = new ArrayList<>();

    @OneToMany(mappedBy = "mammaryScan", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JsonManagedReference
    private List<MasseEchostructure> massesEchostructure = new ArrayList<>();

}