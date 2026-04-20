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

    private boolean distorsionArchitecturale;
    private String optionDistorsionArchitecturale;

    private boolean calcifications;
    private String typesCalcifications;
    private String calcificationsBenignes;
    private String calcificationsSuspectes;
    private String distributionMicrocalcifications;

    // Champ booléen pour décider si les signes associés à la mammographie doivent être remplis
    private boolean isSignesAssociesRequired;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "signes_associes_mammographie", joinColumns = @JoinColumn(name = "mammary_scan_id"))
    @Column(name = "signe")
    private List<String> signesAssociesMammographie;

    // ÉCHOGRAPHIE MAMMAIRE
    private String echostructureMammaire;

    // Champ booléen pour décider si les signes associés à l'échostructure doivent être remplis
    private boolean isSignesAssociesEchostructureRequired;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "signes_associes_echostructure", joinColumns = @JoinColumn(name = "mammary_scan_id"))
    @Column(name = "signe")
    private List<String> signesAssociesEchostructure;

    // Champ booléen pour décider si les cas spéciaux doivent être remplis
    private boolean isCasSpeciauxRequired;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "cas_speciaux", joinColumns = @JoinColumn(name = "mammary_scan_id"))
    private List<CasSpecial> casSpeciaux;

    private String conclusionRadiologue;
    private String conclusionIA;
    private String acrType; // Type ACR (A, B ou C)
    @Column(name = "conduiteatenir", columnDefinition = "TEXT")
    private String conduiteATenir;
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
