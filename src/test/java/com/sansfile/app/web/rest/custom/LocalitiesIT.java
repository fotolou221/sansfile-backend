package com.sansfile.app.web.rest.custom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sansfile.app.IntegrationTest;
import com.sansfile.app.domain.BoutiqueOrder;
import com.sansfile.app.domain.CoiffeurProfile;
import com.sansfile.app.domain.OrderItem;
import com.sansfile.app.domain.Product;
import com.sansfile.app.domain.ProductCategory;
import com.sansfile.app.domain.ProductImage;
import com.sansfile.app.domain.Salon;
import com.sansfile.app.domain.User;
import com.sansfile.app.domain.enumeration.AgentAction;
import com.sansfile.app.domain.enumeration.OrderStatus;
import com.sansfile.app.domain.enumeration.SalonStatus;
import com.sansfile.app.repository.AgentActivityRepository;
import com.sansfile.app.repository.BoutiqueOrderRepository;
import com.sansfile.app.repository.CoiffeurProfileRepository;
import com.sansfile.app.repository.OrderItemRepository;
import com.sansfile.app.repository.PartnerProductRepository;
import com.sansfile.app.repository.ProductCategoryRepository;
import com.sansfile.app.repository.ProductImageRepository;
import com.sansfile.app.repository.ProductRepository;
import com.sansfile.app.repository.SalonRepository;
import com.sansfile.app.repository.UserRepository;
import com.sansfile.app.security.AuthoritiesConstants;
import com.sansfile.app.service.custom.agent.AgentAccountService;
import com.sansfile.app.service.custom.agent.AgentAccountService.AgentForm;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * Localités, partenaires boutique, commandes réparties par localité et agents affectés à des localités.
 * Transactionnel : toutes les données créées sont annulées après chaque test.
 */
@AutoConfigureMockMvc
@IntegrationTest
@Transactional
class LocalitiesIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper om;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SalonRepository salonRepository;

    @Autowired
    private CoiffeurProfileRepository coiffeurProfileRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductCategoryRepository productCategoryRepository;

    @Autowired
    private BoutiqueOrderRepository boutiqueOrderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private PartnerProductRepository partnerProductRepository;

    @Autowired
    private ProductImageRepository productImageRepository;

    @Autowired
    private AgentAccountService agentAccountService;

    @Autowired
    private AgentActivityRepository activityRepository;

    // ── Localités ───────────────────────────────────────────────

    @Test
    void adminManagesLocalitiesAndUsedOnesCannotBeDeleted() throws Exception {
        String name = uniqueName("Rufisque");
        Long id = createLocality(name, 1500);

        mockMvc
            .perform(
                post("/api/admin/localities")
                    .with(admin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json("name", name.toUpperCase()))
            )
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("locality-exists"));

        // Liste publique (sans connexion) : boutique fermée tant qu'il n'y a pas de partenaire
        mockMvc
            .perform(get("/api/public/localities"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == " + id + ")].name").value(hasItem(name)))
            .andExpect(jsonPath("$[?(@.id == " + id + ")].deliveryFee").value(hasItem(1500)))
            .andExpect(jsonPath("$[?(@.id == " + id + ")].shopAvailable").value(hasItem(false)));

        // Désactivée : plus proposée aux clients
        mockMvc
            .perform(
                put("/api/admin/localities/" + id + "/activation")
                    .with(admin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"active\":false}")
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.active").value(false));
        mockMvc.perform(get("/api/public/localities")).andExpect(jsonPath("$[*].id").value(not(hasItem(id.intValue()))));

        // Utilisée par un compte : suppression refusée ; inutilisée : supprimée
        Long used = createLocality(uniqueName("Pikine"), 1000);
        User client = createUser("client");
        client.setLocalityId(used);
        userRepository.saveAndFlush(client);
        mockMvc
            .perform(delete("/api/admin/localities/" + used).with(admin()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("locality-in-use"));
        mockMvc.perform(delete("/api/admin/localities/" + id).with(admin())).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/admin/localities").with(clientAuth(client))).andExpect(status().isForbidden());
    }

    @Test
    void clientChoosesHisLocalityOrAZoneNotYetOpen() throws Exception {
        Long localityId = createLocality(uniqueName("Keur Massar"), 1500);
        User client = createUser("client");

        mockMvc
            .perform(get("/api/account/locality").with(clientAuth(client)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.chosen").value(false))
            .andExpect(jsonPath("$.source").value("NONE"));

        chooseLocality(client, Map.of("localityId", localityId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.localityId").value(localityId))
            .andExpect(jsonPath("$.source").value("USER"))
            .andExpect(jsonPath("$.chosen").value(true));
        assertThat(userRepository.findById(client.getId()).orElseThrow().getLocalityId()).isEqualTo(localityId);

        // Zone pas encore ouverte : enregistrée, puis rattachée automatiquement quand l'admin la crée
        String zone = uniqueName("Thiès");
        chooseLocality(client, Map.of("requestedLocality", "  " + zone + "  "))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.localityId").isEmpty())
            .andExpect(jsonPath("$.requestedLocality").value(zone))
            .andExpect(jsonPath("$.chosen").value(true));
        mockMvc
            .perform(get("/api/admin/localities/requested").with(admin()))
            .andExpect(jsonPath("$[?(@.name == '" + zone + "')].count").value(hasItem(1)));

        Long opened = createLocality(zone.toLowerCase(), 2000);
        User attached = userRepository.findById(client.getId()).orElseThrow();
        assertThat(attached.getLocalityId()).isEqualTo(opened);
        assertThat(attached.getRequestedLocality()).isNull();

        // Localité désactivée : refusée
        mockMvc.perform(
            put("/api/admin/localities/" + localityId + "/activation")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}")
        );
        chooseLocality(client, Map.of("localityId", localityId))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("inactive-locality"));
    }

    @Test
    void coiffeurTakesTheLocalityOfHisSalon() throws Exception {
        Long localityId = createLocality(uniqueName("Colobane"), 1000);
        User coiffeur = createCoiffeur(localityId);

        mockMvc
            .perform(get("/api/account/locality").with(clientAuth(coiffeur)))
            .andExpect(jsonPath("$.source").value("SALON"))
            .andExpect(jsonPath("$.localityId").value(localityId))
            .andExpect(jsonPath("$.chosen").value(true));
        chooseLocality(coiffeur, Map.of("localityId", createLocality(uniqueName("Ailleurs"), 0)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("locality-from-salon"));
    }

    // ── Partenaires et boutique par localité ────────────────────

    @Test
    void partnerOfTheLocalitySuppliesTheShopAndOrdersAreSplit() throws Exception {
        Long localityId = createLocality(uniqueName("Rufisque"), 1500);
        Long otherLocalityId = createLocality(uniqueName("Pikine"), 1000);
        Product clipper = createProduct("Tondeuse", 15_000);
        Product oil = createProduct("Huile", 3_000);
        Product gel = createProduct("Gel", 5_000);
        ProductImage photo = new ProductImage();
        photo.setImageUrl("https://res.cloudinary.com/sansfile/tondeuse.jpg");
        photo.setSortOrder(0);
        photo.setProduct(clipper);
        productImageRepository.saveAndFlush(photo);

        // Sans connexion ou sans localité : boutique vide
        User client = createUser("client");
        mockMvc.perform(get("/api/products")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        shopProducts(client, localityId).andExpect(jsonPath("$.length()").value(0));

        // Sans partenaire : boutique fermée
        client.setLocalityId(localityId);
        userRepository.saveAndFlush(client);
        shopProducts(client, null).andExpect(jsonPath("$.length()").value(0));

        Long partnerId = createPartner(localityId, "Dépôt Rufisque");
        mockMvc
            .perform(get("/api/admin/partners/" + partnerId).with(admin()))
            .andExpect(jsonPath("$.phone").value("+221771234567"))
            .andExpect(jsonPath("$.courierName").value("Moussa"));
        Map<String, Object> second = partnerForm(localityId, "Autre");
        mockMvc
            .perform(
                post("/api/admin/partners").with(admin()).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(second))
            )
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("locality-has-partner"));

        saveOffer(partnerId, clipper.getId(), 12_000, true, 3)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.margin").value(3_000))
            .andExpect(jsonPath("$.stockQuantity").value(3));
        saveOffer(partnerId, oil.getId(), 2_000, true, 5).andExpect(status().isOk());
        saveOffer(partnerId, gel.getId(), 6_000, true, 4)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("wholesale-above-price"));
        saveOffer(partnerId, gel.getId(), 4_000, false, 4).andExpect(status().isOk());

        // Boutique de la localité du compte : produits disponibles chez le partenaire, jamais le prix de gros
        shopProducts(client, null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].id").value(hasItem(clipper.getId().intValue())))
            .andExpect(jsonPath("$[*].id").value(hasItem(oil.getId().intValue())))
            .andExpect(jsonPath("$[*].id").value(not(hasItem(gel.getId().intValue()))))
            .andExpect(content().string(not(containsString("wholesale"))));
        // Une autre localité demandée est ignorée : toujours celle du compte
        shopProducts(client, otherLocalityId).andExpect(jsonPath("$[*].id").value(hasItem(clipper.getId().intValue())));
        mockMvc.perform(get("/api/products/" + clipper.getId()).with(clientAuth(client))).andExpect(status().isOk());
        mockMvc.perform(get("/api/products/" + gel.getId()).with(clientAuth(client))).andExpect(status().isNotFound());

        // Coiffeur : la localité de son salon, même s'il en avait choisi une autre
        shopProducts(createCoiffeur(localityId), otherLocalityId).andExpect(jsonPath("$[*].id").value(hasItem(clipper.getId().intValue())));
        User elsewhere = createCoiffeur(otherLocalityId);
        elsewhere.setLocalityId(localityId);
        userRepository.saveAndFlush(elsewhere);
        shopProducts(elsewhere, localityId).andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/products/" + clipper.getId()).with(clientAuth(elsewhere))).andExpect(status().isNotFound());

        // Administration : tout le catalogue, ou celui d'une localité
        mockMvc
            .perform(get("/api/products").with(admin()).param("size", "1000"))
            .andExpect(jsonPath("$[*].id").value(hasItem(gel.getId().intValue())));
        mockMvc
            .perform(get("/api/products").with(admin()).param("localityId", otherLocalityId.toString()))
            .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/products/" + gel.getId()).with(admin())).andExpect(status().isOk());
        mockMvc
            .perform(get("/api/admin/partners/" + partnerId + "/products").with(admin()))
            .andExpect(jsonPath("$[0].offered").value(true));

        // Prix de vente sous le prix de gros d'un partenaire : refusé
        mockMvc
            .perform(
                patch("/api/products/" + clipper.getId())
                    .with(admin())
                    .contentType("application/merge-patch+json")
                    .content("{\"id\":" + clipper.getId() + ",\"price\":11000}")
            )
            .andExpect(status().isConflict());

        // Devis avant de commander : mêmes montants, article indisponible signalé (et exclu) ; la localité
        // envoyée par l'application est ignorée, c'est celle du compte qui compte
        Map<String, Object> quote = new HashMap<>();
        quote.put("items", List.of(line(clipper, 2), line(oil, 1), line(gel, 1)));
        quote.put("localityId", otherLocalityId);
        mockMvc
            .perform(
                post("/api/orders/quote")
                    .with(clientAuth(client))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsString(quote))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.shopAvailable").value(true))
            .andExpect(jsonPath("$.subtotal").value(33_000))
            .andExpect(jsonPath("$.upfrontAmount").value(8_500))
            .andExpect(jsonPath("$.partnerAmount").value(26_000))
            .andExpect(jsonPath("$.unavailableProductIds[0]").value(gel.getId().intValue()))
            .andExpect(content().string(not(containsString("wholesale"))));
        mockMvc
            .perform(post("/api/orders/quote").contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(quote)))
            .andExpect(status().isUnauthorized());

        // Commande : 2 tondeuses + 1 huile → acompte = part SansFile (7 000) + livraison (1 500), dans la localité
        // du compte même si l'application en envoie une autre
        String checkout = checkout(client, otherLocalityId, List.of(line(clipper, 2), line(oil, 1)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.subtotal").value(33_000))
            .andExpect(jsonPath("$.deliveryFee").value(1_500))
            .andExpect(jsonPath("$.totalPrice").value(34_500))
            .andExpect(jsonPath("$.upfrontAmount").value(8_500))
            .andExpect(jsonPath("$.partnerAmount").value(26_000))
            .andExpect(jsonPath("$.order.partnerName").isEmpty())
            .andExpect(jsonPath("$.order.partnerPhone").isEmpty())
            .andExpect(jsonPath("$.order.invoiceToken").isEmpty())
            .andExpect(jsonPath("$.whatsAppUrl").value(containsString("livreur")))
            .andReturn()
            .getResponse()
            .getContentAsString();
        Long orderId = om.readTree(checkout).get("id").asLong();
        BoutiqueOrder order = boutiqueOrderRepository.findById(orderId).orElseThrow();
        assertThat(order.getPartnerId()).isEqualTo(partnerId);
        assertThat(order.getLocalityId()).isEqualTo(localityId);
        assertThat(order.getCourierName()).isEqualTo("Moussa");
        assertThat(orderItemRepository.findByOrderId(orderId))
            .extracting(OrderItem::getWholesaleUnitPrice)
            .containsExactlyInAnyOrder(12_000L, 2_000L);

        // Produit indisponible chez le partenaire : refusé avec un message clair
        checkout(client, null, List.of(line(gel, 1)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("product-unavailable"))
            .andExpect(jsonPath("$.detail").value(containsString("Gel")));

        // Compte sans localité : refus, même si l'application en envoie une
        User withoutLocality = createUser("client");
        checkout(withoutLocality, localityId, List.of(line(oil, 1)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("locality-required"));
        mockMvc
            .perform(
                post("/api/orders/quote")
                    .with(clientAuth(withoutLocality))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsString(Map.of("items", List.of(line(oil, 1)), "localityId", localityId)))
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("locality-required"));

        // Historique du client : jamais l'identité du partenaire
        mockMvc
            .perform(get("/api/orders/my-orders").with(clientAuth(client)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].upfrontAmount").exists())
            .andExpect(content().string(not(containsString("Dépôt Rufisque"))));

        // Admin : livreur et paiement de la livraison
        mockMvc
            .perform(
                patch("/api/orders/" + orderId + "/courier")
                    .with(admin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"courierName\":\"Ibou\",\"courierPhone\":\"+221 70 000 00 01\",\"courierPaid\":true}")
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.courierName").value("Ibou"))
            .andExpect(jsonPath("$.courierPhone").value("+221700000001"))
            .andExpect(jsonPath("$.courierPaid").value(true))
            .andExpect(jsonPath("$.partnerName").value("Dépôt Rufisque"));
        mockMvc
            .perform(
                patch("/api/orders/" + orderId + "/courier")
                    .with(clientAuth(client))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
            )
            .andExpect(status().isForbidden());

        // Stock : 3 tondeuses chez le partenaire ; on ne commande pas plus que ce qu'il a
        mockMvc
            .perform(
                post("/api/orders/quote")
                    .with(clientAuth(client))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsString(Map.of("items", List.of(line(clipper, 4)))))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stockShortages[0].productId").value(clipper.getId().intValue()))
            .andExpect(jsonPath("$.stockShortages[0].remaining").value(3));
        checkout(client, null, List.of(line(clipper, 4)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("stock-insufficient"));

        // Commande en attente : rien n'est retiré ; acompte reçu : les articles sortent du stock
        assertThat(stockOf(partnerId, clipper)).isEqualTo(3);
        mockMvc
            .perform(post("/api/orders/" + orderId + "/confirm").with(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stockDeducted").value(true))
            .andExpect(jsonPath("$.invoiceToken").value(order.getInvoiceToken()));
        assertThat(stockOf(partnerId, clipper)).isEqualTo(1);
        assertThat(stockOf(partnerId, oil)).isEqualTo(4);

        // Facture du partenaire : photos, quantités, ses prix et ce que son livreur encaisse ; jamais les prix de vente
        mockMvc
            .perform(get("/api/public/invoices/" + order.getInvoiceToken()))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(header().string("Content-Security-Policy", containsString("script-src 'nonce-")))
            .andExpect(header().string("Referrer-Policy", "no-referrer"))
            .andExpect(content().string(containsString(order.getOrderNumber())))
            .andExpect(content().string(containsString(clipper.getTitle())))
            .andExpect(content().string(containsString("https://res.cloudinary.com/sansfile/tondeuse.jpg")))
            .andExpect(content().string(containsString("Quantité : 2")))
            .andExpect(content().string(containsString("Acompte reçu")))
            .andExpect(content().string(containsString(String.format(Locale.FRANCE, "%,d FCFA", 26_000))))
            .andExpect(content().string(not(containsString(String.format(Locale.FRANCE, "%,d", 15_000)))))
            .andExpect(content().string(not(containsString(String.format(Locale.FRANCE, "%,d", 33_000)))));
        mockMvc.perform(get("/api/public/invoices/" + "0".repeat(32))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/public/invoices/pas-un-lien")).andExpect(status().isNotFound());

        // Rupture : produit invisible, et une commande en attente ne peut plus être confirmée
        String secondCheckout = checkout(client, null, List.of(line(clipper, 1)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
        Long secondId = om.readTree(secondCheckout).get("id").asLong();
        saveOffer(partnerId, clipper.getId(), 12_000, true, 0).andExpect(status().isOk());
        shopProducts(client, null).andExpect(jsonPath("$[*].id").value(not(hasItem(clipper.getId().intValue()))));
        mockMvc
            .perform(post("/api/orders/" + secondId + "/confirm").with(admin()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("stock-insufficient"));
        assertThat(boutiqueOrderRepository.findById(secondId).orElseThrow().getStatus()).isEqualTo(OrderStatus.EN_ATTENTE);
        assertThat(stockOf(partnerId, oil)).isEqualTo(4);

        // Commande annulée après l'acompte : ses articles reviennent en stock
        setStatus(orderId, "ANNULE").andExpect(status().isOk()).andExpect(jsonPath("$.stockDeducted").value(false));
        assertThat(stockOf(partnerId, clipper)).isEqualTo(2);
        assertThat(stockOf(partnerId, oil)).isEqualTo(5);
        shopProducts(client, null).andExpect(jsonPath("$[*].id").value(hasItem(clipper.getId().intValue())));
        mockMvc
            .perform(get("/api/public/invoices/" + order.getInvoiceToken()))
            .andExpect(content().string(containsString("Commande annulée")));

        // Partenaire désactivé : boutique fermée dans la localité
        mockMvc
            .perform(
                put("/api/admin/partners/" + partnerId + "/activation")
                    .with(admin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"active\":false}")
            )
            .andExpect(status().isOk());
        shopProducts(client, null).andExpect(jsonPath("$.length()").value(0));
        checkout(client, null, List.of(line(oil, 1)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("shop-unavailable"));

        // Partenaire avec commandes : suppression refusée
        mockMvc
            .perform(delete("/api/admin/partners/" + partnerId).with(admin()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("partner-has-orders"));
    }

    // ── Agents de terrain ───────────────────────────────────────

    @Test
    void agentsWorkOnlyInTheirLocalities() throws Exception {
        Long rufisque = createLocality(uniqueName("Rufisque"), 1000);
        Long pikine = createLocality(uniqueName("Pikine"), 1000);
        String email = createActiveAgent();
        Long agentId = userRepository.findOneByLogin(email).orElseThrow().getId();

        // Sans localité : connexion possible, mais ni inscription ni modification de salon
        mockMvc
            .perform(get("/api/agent/me").with(agent(email)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.localities.length()").value(0));
        registerSalon(email, rufisque).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("no-locality"));

        mockMvc
            .perform(
                put("/api/admin/agents/" + agentId + "/localities")
                    .with(admin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"localityIds\":[" + rufisque + "]}")
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.localities[0].id").value(rufisque));
        assertThat(activityRepository.findAll()).anyMatch(
            a -> agentId.equals(a.getAgentId()) && a.getAction() == AgentAction.LOCALITIES_UPDATED
        );
        mockMvc.perform(get("/api/agent/me").with(agent(email))).andExpect(jsonPath("$.localities[0].id").value(rufisque));

        registerSalon(email, pikine).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("locality-not-assigned"));
        String created = registerSalon(email, rufisque)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.localityId").value(rufisque))
            .andExpect(jsonPath("$.localityName").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();
        Long ownSalon = om.readTree(created).get("id").asLong();

        // Salon de sa localité inscrit par l'admin : modifiable ; salon d'une autre localité : refusé
        Salon zoneSalon = salonRepository.saveAndFlush(newSalon(rufisque, null));
        Salon otherSalon = salonRepository.saveAndFlush(newSalon(pikine, null));
        patchSalon(email, zoneSalon.getId(), "Salon Corrigé Zone").andExpect(status().isOk());
        patchSalon(email, otherSalon.getId(), "Piratage").andExpect(status().isForbidden());
        mockMvc
            .perform(get("/api/agent/zone-salons").with(agent(email)))
            .andExpect(jsonPath("$[*].id").value(hasItem(ownSalon.intValue())))
            .andExpect(jsonPath("$[*].id").value(hasItem(zoneSalon.getId().intValue())))
            .andExpect(jsonPath("$[*].id").value(not(hasItem(otherSalon.getId().intValue()))));

        // Désaffecté : effet immédiat, ses salons restent consultables
        mockMvc
            .perform(
                put("/api/admin/agents/" + agentId + "/localities")
                    .with(admin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"localityIds\":[]}")
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.localities.length()").value(0));
        patchSalon(email, ownSalon, "Plus le droit").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("no-locality"));
        mockMvc
            .perform(get("/api/agent/salons").with(agent(email)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].id").value(hasItem(ownSalon.intValue())));

        // Affectation automatique d'après les localités de ses salons
        mockMvc
            .perform(post("/api/admin/agents/auto-assign-localities").with(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.agentsAssigned").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
        mockMvc.perform(get("/api/agent/me").with(agent(email))).andExpect(jsonPath("$.localities[0].id").value(rufisque));
    }

    // ── Outils ──────────────────────────────────────────────────

    private Long createLocality(String name, long fee) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("deliveryFee", fee);
        String response = mockMvc
            .perform(
                post("/api/admin/localities").with(admin()).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body))
            )
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return om.readTree(response).get("id").asLong();
    }

    private Map<String, Object> partnerForm(Long localityId, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("managerName", "Awa");
        body.put("phone", "77 123 45 67");
        body.put("localityId", localityId);
        body.put("courierName", "Moussa");
        body.put("courierPhone", "70 111 22 33");
        return body;
    }

    private Long createPartner(Long localityId, String name) throws Exception {
        String response = mockMvc
            .perform(
                post("/api/admin/partners")
                    .with(admin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsString(partnerForm(localityId, name)))
            )
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return om.readTree(response).get("id").asLong();
    }

    private ResultActions saveOffer(Long partnerId, Long productId, long wholesale, boolean available, int stock) throws Exception {
        return mockMvc.perform(
            put("/api/admin/partners/" + partnerId + "/products/" + productId)
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"wholesalePrice\":" + wholesale + ",\"available\":" + available + ",\"stockQuantity\":" + stock + "}")
        );
    }

    private int stockOf(Long partnerId, Product product) {
        return partnerProductRepository.findOneByPartnerIdAndProductId(partnerId, product.getId()).orElseThrow().getStockQuantity();
    }

    private ResultActions setStatus(Long orderId, String status) throws Exception {
        return mockMvc.perform(
            patch("/api/orders/" + orderId + "/status")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + status + "\"}")
        );
    }

    private Product createProduct(String title, long price) {
        ProductCategory category = productCategoryRepository
            .findAll()
            .stream()
            .findFirst()
            .orElseGet(() -> {
                ProductCategory c = new ProductCategory();
                c.setName("Catégorie test");
                c.setSlug("categorie-test-" + UUID.randomUUID().toString().substring(0, 6));
                return productCategoryRepository.saveAndFlush(c);
            });
        Product product = new Product();
        product.setBrand("SansFile");
        product.setTitle(title + " " + UUID.randomUUID().toString().substring(0, 6));
        product.setPrice(price);
        product.setInStock(true);
        product.setCategory(category);
        product.setCreatedDate(Instant.now());
        return productRepository.saveAndFlush(product);
    }

    private static Map<String, Object> line(Product product, int quantity) {
        return Map.of("productId", product.getId(), "quantity", quantity);
    }

    /** Produits de la boutique vus par ce compte ({@code sentLocalityId} : localité demandée, ignorée). */
    private ResultActions shopProducts(User user, Long sentLocalityId) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/products").with(clientAuth(user)).param("size", "500");
        return mockMvc.perform(sentLocalityId == null ? request : request.param("localityId", sentLocalityId.toString()));
    }

    /** Commande de ce compte ({@code sentLocalityId} : localité envoyée par l'application, ignorée). */
    private ResultActions checkout(User client, Long sentLocalityId, List<Map<String, Object>> items) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("items", items);
        body.put("orderType", "WHATSAPP");
        body.put("localityId", sentLocalityId);
        body.put("latitude", 14.7167);
        body.put("longitude", -17.4677);
        return mockMvc.perform(
            withIp(
                post("/api/orders/checkout")
                    .with(clientAuth(client))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(om.writeValueAsString(body))
            )
        );
    }

    private ResultActions chooseLocality(User user, Map<String, Object> body) throws Exception {
        return mockMvc.perform(
            put("/api/account/locality").with(clientAuth(user)).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body))
        );
    }

    private ResultActions registerSalon(String email, Long localityId) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> salon = new HashMap<>();
        salon.put("name", "Salon Terrain " + suffix);
        salon.put("slug", "salon-terrain-" + suffix);
        salon.put("location", "Dakar");
        salon.put("district", "Quartier");
        salon.put("phone", "+22176" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999));
        salon.put("ownerName", "Ibrahima Fall");
        salon.put("status", "CLOSED");
        salon.put("active", true);
        salon.put("localityId", localityId);
        return mockMvc.perform(
            post("/api/salons").with(agent(email)).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(salon))
        );
    }

    private ResultActions patchSalon(String email, Long salonId, String name) throws Exception {
        return mockMvc.perform(
            patch("/api/salons/" + salonId)
                .with(agent(email))
                .contentType("application/merge-patch+json")
                .content(om.writeValueAsString(Map.of("id", salonId, "name", name)))
        );
    }

    private Salon newSalon(Long localityId, Long agentId) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Salon salon = new Salon();
        salon.setName("Salon " + suffix);
        salon.setSlug("salon-" + suffix);
        salon.setLocation("Dakar");
        salon.setDistrict("Quartier");
        salon.setStatus(SalonStatus.CLOSED);
        salon.setActive(true);
        salon.setPeopleWaiting(0);
        salon.setEstimatedWaitMinutes(0);
        salon.setCreatedDate(Instant.now());
        salon.setLastModifiedDate(Instant.now());
        salon.setLocalityId(localityId);
        salon.setCreatedByAgentId(agentId);
        return salon;
    }

    private User createUser(String role) {
        String phone = "+22177" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999);
        User user = new User();
        user.setLogin(phone);
        user.setPhone(phone);
        user.setPassword("$2a$10$" + "x".repeat(53));
        user.setFirstName(role.equals("coiffeur") ? "Barbier" : "Client");
        user.setLastName("Test");
        user.setEmail(phone.substring(1) + "@test.sansfile.sn");
        user.setActivated(true);
        user.setLangKey("fr");
        return userRepository.saveAndFlush(user);
    }

    /** Coiffeur dont le salon est dans cette localité. */
    private User createCoiffeur(Long salonLocalityId) {
        User coiffeur = createUser("coiffeur");
        Salon salon = salonRepository.saveAndFlush(newSalon(salonLocalityId, null));
        CoiffeurProfile profile = new CoiffeurProfile();
        profile.setName("Barbier Test");
        profile.setPhone(coiffeur.getLogin());
        profile.setActive(true);
        profile.setSalon(salon);
        profile.setUser(coiffeur);
        profile.setCreatedDate(Instant.now());
        coiffeurProfileRepository.saveAndFlush(profile);
        return coiffeur;
    }

    private String createActiveAgent() {
        String email = "agent-" + UUID.randomUUID().toString().substring(0, 8) + "@terrain.sn";
        agentAccountService.createAgent(new AgentForm("Moussa", "Sarr", email, null));
        User agent = userRepository.findOneByLogin(email).orElseThrow();
        agent.setMustChangePassword(false);
        userRepository.saveAndFlush(agent);
        return email;
    }

    private String json(String key, Object value) throws Exception {
        return om.writeValueAsString(Map.of(key, value));
    }

    private static String uniqueName(String base) {
        return base + " " + UUID.randomUUID().toString().substring(0, 6);
    }

    private static MockHttpServletRequestBuilder withIp(MockHttpServletRequestBuilder request) {
        return request.with(r -> {
            r.setRemoteAddr("10.88.0." + ThreadLocalRandom.current().nextInt(1, 250));
            return r;
        });
    }

    private static RequestPostProcessor clientAuth(User user) {
        return user(user.getLogin()).authorities(
            new SimpleGrantedAuthority(AuthoritiesConstants.USER),
            new SimpleGrantedAuthority(AuthoritiesConstants.CLIENT)
        );
    }

    private static RequestPostProcessor agent(String email) {
        return user(email).authorities(
            new SimpleGrantedAuthority(AuthoritiesConstants.USER),
            new SimpleGrantedAuthority(AuthoritiesConstants.AGENT)
        );
    }

    private static RequestPostProcessor admin() {
        return user("admin-localities-it").authorities(new SimpleGrantedAuthority(AuthoritiesConstants.ADMIN));
    }
}
