import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public class CatalogoMariaGomes {
    private static final Path DATA_DIR = Path.of("data");
    private static final Path UPLOAD_DIR = Path.of("uploads");
    private static final Path PRODUCTS_FILE = DATA_DIR.resolve("produtos.tsv");
    private static final Path MOVEMENTS_FILE = DATA_DIR.resolve("movimentacoes.tsv");
    private static final Path CATALOG_SEEDED_FILE = DATA_DIR.resolve("catalogo_mahluz_inicializado.flag");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final String WHATSAPP_NUMBER = "5511988673088";
    private static final String ADMIN_PASSWORD = System.getenv().getOrDefault("ADMIN_PASSWORD", "admin-" + UUID.randomUUID().toString().substring(0, 8));
    private static final String ADMIN_COOKIE = "mahluz_admin";
    private static final String ADMIN_TOKEN = UUID.randomUUID().toString();
    private static final Store STORE = new Store();

    public static void main(String[] args) throws Exception {
        Files.createDirectories(DATA_DIR);
        Files.createDirectories(UPLOAD_DIR);
        STORE.load();
        STORE.seedExamplesIfNeeded();
        STORE.clearGeneratedImageRefs();

        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", CatalogoMariaGomes::route);
        server.setExecutor(null);
        server.start();

        System.out.println("Catalogo Maria Gomes no ar: http://localhost:" + port);
        System.out.println("Painel administrativo: http://localhost:" + port + "/admin");
        if (System.getenv("ADMIN_PASSWORD") == null) {
            System.out.println("Senha temporaria do admin: " + ADMIN_PASSWORD);
        }
    }

    private static void route(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            boolean readRequest = method.equals("GET") || method.equals("HEAD");

            if ((path.equals("/") || path.equals("/catalogo")) && readRequest) {
                catalog(exchange);
            } else if (path.equals("/login") && readRequest) {
                loginForm(exchange);
            } else if (path.equals("/login") && method.equals("POST")) {
                login(exchange);
            } else if (path.equals("/sair") && readRequest) {
                logout(exchange);
            } else if (path.equals("/admin") && readRequest) {
                if (!requireAdmin(exchange)) return;
                admin(exchange);
            } else if (path.equals("/novo") && readRequest) {
                if (!requireAdmin(exchange)) return;
                productForm(exchange, null);
            } else if (path.equals("/produtos") && method.equals("POST")) {
                if (!requireAdmin(exchange)) return;
                createProduct(exchange);
            } else if (path.startsWith("/editar/") && readRequest) {
                if (!requireAdmin(exchange)) return;
                editProduct(exchange, path);
            } else if (path.startsWith("/atualizar/") && method.equals("POST")) {
                if (!requireAdmin(exchange)) return;
                updateProduct(exchange, path);
            } else if (path.startsWith("/excluir/") && method.equals("POST")) {
                if (!requireAdmin(exchange)) return;
                deleteProduct(exchange, path);
            } else if (path.equals("/movimentacao") && method.equals("POST")) {
                if (!requireAdmin(exchange)) return;
                createMovement(exchange);
            } else if (path.startsWith("/uploads/") && readRequest) {
                serveUpload(exchange, path);
            } else if (readRequest) {
                catalog(exchange);
            } else {
                send(exchange, 404, page("Nao encontrado", "<main class='wrap'><h1>Pagina nao encontrada</h1></main>"));
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            send(exchange, 500, page("Erro", "<main class='wrap'><h1>Erro interno</h1><p>" + esc(ex.getMessage()) + "</p></main>"));
        }
    }

    private static boolean requireAdmin(HttpExchange exchange) throws IOException {
        if (isAdmin(exchange)) return true;
        if (exchange.getRequestMethod().equals("GET") || exchange.getRequestMethod().equals("HEAD")) {
            redirect(exchange, "/login");
            return false;
        }
        throw new IllegalArgumentException("Acesso restrito ao administrador");
    }

    private static boolean isAdmin(HttpExchange exchange) {
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) return false;
        for (String item : cookie.split(";")) {
            String[] pair = item.trim().split("=", 2);
            if (pair.length == 2 && pair[0].equals(ADMIN_COOKIE) && pair[1].equals(ADMIN_TOKEN)) return true;
        }
        return false;
    }

    private static void loginForm(HttpExchange exchange) throws IOException {
        if (isAdmin(exchange)) {
            redirect(exchange, "/admin");
            return;
        }
        StringBuilder html = new StringBuilder();
        html.append("<main class='wrap narrow login-card'><p class='eyebrow'>Acesso administrativo</p><h1>Entrar no painel</h1>");
        html.append("<p class='form-hint'>Area reservada para cadastro de produtos, fotos, precos e estoque.</p>");
        html.append("<form class='form' method='post' action='/login'>");
        html.append("<label>Senha<input type='password' name='password' autocomplete='current-password' required></label>");
        html.append("<button class='button' type='submit'>Entrar</button></form></main>");
        send(exchange, 200, page("Login - MAHLUZ", html.toString()));
    }

    private static void login(HttpExchange exchange) throws IOException {
        Map<String, String> form = urlEncoded(exchange);
        if (Objects.equals(form.getOrDefault("password", ""), ADMIN_PASSWORD)) {
            exchange.getResponseHeaders().add("Set-Cookie", ADMIN_COOKIE + "=" + ADMIN_TOKEN + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=86400");
            redirect(exchange, "/admin");
            return;
        }
        send(exchange, 403, page("Login - MAHLUZ", "<main class='wrap narrow login-card'><h1>Senha incorreta</h1><p class='form-hint'>Tente novamente para acessar o painel administrativo.</p><a class='button' href='/login'>Voltar</a></main>"));
    }

    private static void logout(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Set-Cookie", ADMIN_COOKIE + "=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0");
        redirect(exchange, "/catalogo");
    }

    private static void catalog(HttpExchange exchange) throws IOException {
        StringBuilder html = new StringBuilder();
        List<Product> visibleProducts = STORE.products().stream().filter(p -> p.active).toList();
        List<Product> limitedProducts = visibleProducts.stream().filter(p -> p.stock <= 2).toList();
        List<Product> featuredProducts = visibleProducts.stream().filter(p -> p.stock > 0).limit(6).toList();
        List<Product> newProducts = visibleProducts.stream().limit(4).toList();
        List<Product> eleganceProducts = visibleProducts.stream().filter(p -> containsAny(p, "bolsa", "colar", "elegance", "milano", "verona")).limit(4).toList();
        if (eleganceProducts.isEmpty()) eleganceProducts = featuredProducts;
        List<Product> auraProducts = visibleProducts.stream().filter(p -> containsAny(p, "brinco", "pulseira", "anel", "luna", "aurora")).limit(4).toList();
        if (auraProducts.isEmpty()) auraProducts = limitedProducts.isEmpty() ? featuredProducts : limitedProducts;

        html.append("<section class='client-hero boutique-hero'><div class='brand-lockup'>").append(logoSvg("hero-logo")).append("</div>");
        html.append("<div class='hero-copy'><p class='eyebrow'>Boutique digital</p><h1>Peças escolhidas com carinho para momentos que pedem brilho.</h1>");
        html.append("<p>Uma curadoria delicada de semijoias e bolsas em quantidades limitadas. Favorite suas escolhas e chame no WhatsApp para reservar.</p>");
        html.append("<div class='hero-actions'><a class='button' href='").append(whatsappUrl("Ola, vim pelo catalogo MAHLUZ Semi Joias e quero ver as pecas disponiveis")).append("' target='_blank'>Atendimento pelo WhatsApp</a></div></div>");
        html.append("<div class='hero-note'><span>Atendimento humano</span><span>Curadoria exclusiva</span><span>Peças limitadas</span></div></section>");

        String itemLabel = visibleProducts.size() == 1 ? " peça selecionada" : " peças selecionadas";
        html.append("<main class='boutique-main'><section class='intro-strip'><p>MAHLUZ Semi Joias</p><h2>Catálogo como coleção, pensado para escolher com calma.</h2><span>").append(visibleProducts.size()).append(itemLabel).append("</span></section>");
        if (visibleProducts.isEmpty()) {
            html.append("<div class='empty'>Novas peças serão cadastradas em breve.</div>");
        }
        html.append(collectionSection("Chegou Hoje", "Novidades selecionadas para quem gosta de ver primeiro.", newProducts, true));
        html.append(collectionSection("Peças Exclusivas", "Quase únicas, disponíveis por pouco tempo.", limitedProducts.isEmpty() ? featuredProducts : limitedProducts, false));
        if (visibleProducts.size() > 2) {
            html.append(collectionSection("Destaques da Semana", "Escolhas com mais presença para elevar a produção.", featuredProducts, false));
            html.append(collectionSection("Coleção Elegance", "Bolsas e peças com acabamento sofisticado.", eleganceProducts, false));
            html.append(collectionSection("Coleção Aura", "Delicadeza, brilho e feminilidade na medida certa.", auraProducts, false));
            html.append(combineSection(visibleProducts));
        }
        html.append("<section class='favorites-panel' id='favoritos'><div><p class='eyebrow'>Favoritos</p><h2>Suas escolhas salvas</h2><p>Toque no coração das peças para montar sua seleção antes de chamar no WhatsApp.</p></div><div class='favorite-list' data-favorite-list><span>Nenhuma peça favoritada ainda.</span></div></section>");
        html.append("</main>");
        send(exchange, 200, page("MAHLUZ Semi Joias - Catalogo", html.toString()));
    }

    private static void admin(HttpExchange exchange) throws IOException {
        StringBuilder html = new StringBuilder();
        html.append("<main class='wrap admin'><div class='topbar'><div><p class='eyebrow'>MAHLUZ Semi Joias</p><h1>Controle do catalogo</h1></div><div class='admin-menu'><a class='button secondary-button' href='/catalogo'>Ver catalogo</a><a class='button secondary-button' href='/sair'>Sair</a><a class='button' href='/novo'>Cadastrar produto</a></div></div>");
        html.append("<section class='stats'><div><b>").append(STORE.products().size()).append("</b><span>produtos</span></div>");
        html.append("<div><b>").append(STORE.totalStock()).append("</b><span>itens em estoque</span></div>");
        html.append("<div><b>").append(money(STORE.stockValue())).append("</b><span>valor em estoque</span></div></section>");
        html.append("<section class='panel'><div class='panel-head'><div><h2>Produtos cadastrados</h2><p>Edite foto, descricao, preco e estoque sempre que precisar.</p></div><a class='button mini-button' href='/novo'>Adicionar</a></div><table><thead><tr><th>Foto</th><th>Produto</th><th>Categoria</th><th>Preco</th><th>Estoque</th><th>Acoes</th></tr></thead><tbody>");
        for (Product p : STORE.products()) {
            html.append("<tr><td class='photo-cell'>").append(adminImageTag(p)).append("</td><td>").append(esc(p.name)).append(p.active ? "" : " <small>inativo</small>").append("</td><td>").append(esc(p.category)).append("</td><td>").append(money(p.price)).append("</td><td>").append(p.stock).append("</td><td class='actions'>");
            html.append("<a href='/editar/").append(p.id).append("'>Editar</a>");
            html.append("<form method='post' action='/excluir/").append(p.id).append("'><button>Excluir</button></form></td></tr>");
        }
        html.append("</tbody></table></section>");
        html.append("<section class='panel split'><div><h2>Registrar entrada ou venda</h2>").append(movementForm()).append("</div><div><h2>Ultimas movimentacoes</h2>").append(movementList()).append("</div></section></main>");
        send(exchange, 200, page("Painel - Maria Gomes", html.toString()));
    }

    private static void productForm(HttpExchange exchange, Product p) throws IOException {
        boolean editing = p != null;
        String action = editing ? "/atualizar/" + p.id : "/produtos";
        StringBuilder html = new StringBuilder();
        html.append("<main class='wrap narrow'><a href='/admin' class='back'>Voltar ao painel</a><h1>").append(editing ? "Editar produto" : "Cadastrar produto").append("</h1>");
        html.append("<p class='form-hint'>Preencha nome, descricao, preco, estoque e foto. Depois de salvar, a peca aparece no catalogo se estiver marcada como vitrine.</p>");
        html.append("<form class='form' method='post' enctype='multipart/form-data' action='").append(action).append("'>");
        html.append(input("name", "Nome", editing ? p.name : "", true));
        html.append(input("category", "Categoria", editing ? p.category : "Semijoias", true));
        html.append("<div class='field-grid'>").append(input("price", "Preco", editing ? p.price.toPlainString() : "", true));
        html.append(input("stock", "Estoque atual", editing ? String.valueOf(p.stock) : "1", true)).append("</div>");
        html.append("<label>Descricao<textarea name='description' rows='4'>").append(editing ? esc(p.description) : "").append("</textarea></label>");
        if (editing && p.image != null && !p.image.isBlank() && Files.exists(UPLOAD_DIR.resolve(p.image))) {
            html.append("<div class='current-photo'><span>Foto atual</span>").append(imageTag(p)).append("</div>");
            html.append("<label class='check danger-check'><input type='checkbox' name='removeImage'> Remover foto atual</label>");
        }
        html.append("<label>Foto do produto<input type='file' name='image' accept='image/*'><small>Use uma foto clara, bem enquadrada e focada na peca.</small></label>");
        html.append("<label class='check'><input type='checkbox' name='active' ").append(!editing || p.active ? "checked" : "").append("> Aparecer na vitrine</label>");
        html.append("<button class='button' type='submit'>Salvar produto</button></form></main>");
        send(exchange, 200, page(editing ? "Editar produto" : "Novo produto", html.toString()));
    }

    private static void createProduct(HttpExchange exchange) throws IOException {
        Map<String, Part> parts = multipart(exchange);
        Product p = new Product();
        p.id = UUID.randomUUID().toString();
        fillProduct(p, parts);
        p.image = saveImage(parts.get("image"), "");
        STORE.addProduct(p);
        redirect(exchange, "/admin");
    }

    private static void updateProduct(HttpExchange exchange, String path) throws IOException {
        String id = path.substring("/atualizar/".length());
        Product p = STORE.findProduct(id).orElseThrow(() -> new IllegalArgumentException("Produto nao encontrado"));
        Map<String, Part> parts = multipart(exchange);
        fillProduct(p, parts);
        if (parts.containsKey("removeImage")) p.image = "";
        p.image = saveImage(parts.get("image"), p.image);
        STORE.save();
        redirect(exchange, "/admin");
    }

    private static void deleteProduct(HttpExchange exchange, String path) throws IOException {
        String id = path.substring("/excluir/".length());
        STORE.removeProduct(id);
        redirect(exchange, "/admin");
    }

    private static void editProduct(HttpExchange exchange, String path) throws IOException {
        String id = path.substring("/editar/".length());
        Product p = STORE.findProduct(id).orElseThrow(() -> new IllegalArgumentException("Produto nao encontrado"));
        productForm(exchange, p);
    }

    private static void createMovement(HttpExchange exchange) throws IOException {
        Map<String, String> form = urlEncoded(exchange);
        String id = form.getOrDefault("productId", "");
        Product p = STORE.findProduct(id).orElseThrow(() -> new IllegalArgumentException("Produto nao encontrado"));
        String type = form.getOrDefault("type", "ENTRADA");
        int quantity = Math.max(1, Integer.parseInt(form.getOrDefault("quantity", "1")));
        if (type.equals("SAIDA") && p.stock < quantity) {
            throw new IllegalArgumentException("Estoque insuficiente para esta saida");
        }
        p.stock += type.equals("ENTRADA") ? quantity : -quantity;

        Movement m = new Movement();
        m.id = UUID.randomUUID().toString();
        m.productId = p.id;
        m.productName = p.name;
        m.type = type;
        m.quantity = quantity;
        m.note = form.getOrDefault("note", "");
        m.when = LocalDateTime.now();
        STORE.addMovement(m);
        redirect(exchange, "/admin");
    }

    private static void fillProduct(Product p, Map<String, Part> parts) {
        p.name = value(parts, "name");
        p.category = value(parts, "category");
        p.description = value(parts, "description");
        p.price = new BigDecimal(value(parts, "price").replace(",", "."));
        p.stock = Integer.parseInt(value(parts, "stock"));
        p.active = parts.containsKey("active");
    }

    private static String movementForm() {
        StringBuilder html = new StringBuilder();
        html.append("<form class='form compact' method='post' action='/movimentacao'><label>Produto<select name='productId'>");
        for (Product p : STORE.products()) html.append("<option value='").append(p.id).append("'>").append(esc(p.name)).append(" - estoque ").append(p.stock).append("</option>");
        html.append("</select></label><label>Tipo<select name='type'><option value='ENTRADA'>Entrada/compra</option><option value='SAIDA'>Saida/venda</option></select></label>");
        html.append("<label>Quantidade<input name='quantity' type='number' min='1' value='1'></label>");
        html.append("<label>Observacao<input name='note' placeholder='Cliente, fornecedor ou detalhe'></label>");
        html.append("<button class='button' type='submit'>Registrar</button></form>");
        return html.toString();
    }

    private static String movementList() {
        StringBuilder html = new StringBuilder("<div class='timeline'>");
        STORE.movements().stream().sorted(Comparator.comparing((Movement m) -> m.when).reversed()).limit(8).forEach(m -> {
            html.append("<div><b>").append(m.type.equals("ENTRADA") ? "+" : "-").append(m.quantity).append(" ").append(esc(m.productName)).append("</b>");
            html.append("<span>").append(m.when.format(DATE_FORMAT)).append(" - ").append(esc(m.note)).append("</span></div>");
        });
        return html.append("</div>").toString();
    }

    private static String input(String name, String label, String value, boolean required) {
        return "<label>" + label + "<input name='" + name + "' value='" + esc(value) + "'" + (required ? " required" : "") + "></label>";
    }

    private static String imageTag(Product p) {
        if (p.image == null || p.image.isBlank()) return "<div class='placeholder'>MAHLUZ</div>";
        if (p.image.startsWith("http://") || p.image.startsWith("https://")) return "<img src='" + esc(p.image) + "' alt='" + esc(p.name) + "'>";
        if (!Files.exists(UPLOAD_DIR.resolve(p.image))) return "<div class='placeholder'>MAHLUZ</div>";
        return "<img src='/uploads/" + esc(p.image) + "' alt='" + esc(p.name) + "'>";
    }

    private static String adminImageTag(Product p) {
        if (p.image == null || p.image.isBlank() || !Files.exists(UPLOAD_DIR.resolve(p.image))) return "<span class='photo-empty'>Sem foto</span>";
        return "<img class='photo-thumb' src='/uploads/" + esc(p.image) + "' alt='" + esc(p.name) + "'>";
    }

    private static String collectionSection(String title, String subtitle, List<Product> products, boolean arrival) {
        if (products.isEmpty()) return "";
        StringBuilder html = new StringBuilder();
        html.append("<section class='collection ").append(arrival ? "arrival" : "").append("'><div class='collection-head'><div><p class='eyebrow'>").append(arrival ? "Chegou hoje" : "Curadoria MAHLUZ").append("</p>");
        html.append("<h2>").append(esc(title)).append("</h2><p>").append(esc(subtitle)).append("</p></div></div>");
        html.append("<div class='boutique-grid'>");
        for (int i = 0; i < products.size(); i++) {
            html.append(productCard(products.get(i), i, arrival));
        }
        html.append("</div></section>");
        return html.toString();
    }

    private static String productCard(Product p, int index, boolean arrival) {
        String tag = arrival ? "👜 Novo" : productTag(p, index);
        String stockText = stockLabel(p);
        String favoriteLabel = "Favoritar " + p.name;
        StringBuilder html = new StringBuilder();
        html.append("<article class='boutique-card fade-in' data-product-id='").append(esc(p.id)).append("' data-product-name='").append(esc(p.name)).append("'>");
        html.append("<div class='image-wrap'>").append(imageTag(p)).append("<button class='favorite-button' type='button' aria-label='").append(esc(favoriteLabel)).append("' data-favorite-toggle data-id='").append(esc(p.id)).append("' data-name='").append(esc(p.name)).append("'>♡</button>");
        html.append("<span class='product-badge'>").append(tag).append("</span></div>");
        html.append("<div class='boutique-card-body'><div class='product-kicker'><span>").append(esc(p.category)).append("</span><span>").append(esc(stockText)).append("</span></div>");
        html.append("<h3>").append(esc(displayName(p, index))).append("</h3>");
        html.append("<p>").append(esc(curatedDescription(p))).append("</p>");
        html.append("<div class='reserve-row'><strong>").append(money(p.price)).append("</strong>");
        html.append("<a class='reserve-button' target='_blank' href='").append(reserveUrl(p)).append("'>Reservar no WhatsApp</a></div></div>");
        html.append("</article>");
        return html.toString();
    }

    private static String combineSection(List<Product> products) {
        if (products.size() < 2) return "";
        StringBuilder html = new StringBuilder();
        html.append("<section class='combine-section'><div class='collection-head'><div><p class='eyebrow'>Combine com</p><h2>Duplas que valorizam o look</h2><p>Sugestões para montar uma composição completa antes de reservar.</p></div></div><div class='combine-grid'>");
        int pairs = Math.min(3, products.size() / 2);
        for (int i = 0; i < pairs; i++) {
            Product first = products.get(i * 2);
            Product second = products.get(i * 2 + 1);
            html.append("<article class='combine-card'><div class='mini-images'>").append(imageTag(first)).append(imageTag(second)).append("</div>");
            html.append("<div><span>Seleção coordenada</span><h3>").append(esc(displayName(first, i))).append(" + ").append(esc(displayName(second, i + 1))).append("</h3>");
            html.append("<p>Uma combinação pensada para criar presença sem perder delicadeza.</p>");
            html.append("<a target='_blank' href='").append(combineReserveUrl(first, second)).append("'>Reservar a combinação</a></div></article>");
        }
        html.append("</div></section>");
        return html.toString();
    }

    private static boolean containsAny(Product p, String... terms) {
        String text = (p.name + " " + p.category + " " + p.description).toLowerCase(Locale.ROOT);
        for (String term : terms) {
            if (text.contains(term)) return true;
        }
        return false;
    }

    private static String productTag(Product p, int index) {
        if (p.stock <= 1) return "💎 Peça única";
        if (p.stock == 2) return "✨ Exclusivo";
        if (index % 5 == 0) return "🔥 Alta procura";
        if (index % 3 == 0) return "❤️ Favorito";
        return "✨ Exclusivo";
    }

    private static String stockLabel(Product p) {
        if (p.stock <= 0) return "Reservas indisponíveis";
        if (p.stock == 1) return "Última peça disponível";
        if (p.stock <= 3) return "Quantidade limitada";
        return "Disponível para reserva";
    }

    private static String displayName(Product p, int index) {
        if (p.name.matches("(?i).*[0-9]{2,}.*")) {
            String[] names = {"Milano", "Verona", "Aurora", "Luna", "Elegance", "Aura", "Bella", "Siena"};
            return names[Math.abs(index) % names.length];
        }
        return p.name;
    }

    private static String curatedDescription(Product p) {
        if (p.description != null && !p.description.isBlank() && p.description.length() <= 170) return p.description;
        if (containsAny(p, "bolsa")) return "Bolsa elegante perfeita para ocasiões especiais e produções marcantes.";
        if (containsAny(p, "colar")) return "Colar delicado para iluminar o colo com um brilho sofisticado.";
        if (containsAny(p, "brinco")) return "Brinco feminino que traz leveza e presença na medida certa.";
        if (containsAny(p, "pulseira")) return "Pulseira versátil para compor combinações delicadas todos os dias.";
        return "Peça escolhida com carinho para trazer charme, brilho e personalidade.";
    }

    private static String reserveUrl(Product p) {
        String message = "Oi! Vi a peça " + displayName(p, 0) + " no catálogo 😍";
        return whatsappUrl(message);
    }

    private static String combineReserveUrl(Product first, Product second) {
        String message = "Oi! Vi a combinação " + first.name + " + " + second.name + " no catálogo 😍";
        return whatsappUrl(message);
    }

    private static String whatsappUrl(String message) {
        return "https://wa.me/" + WHATSAPP_NUMBER + "?text=" + URLEncoder.encode(message, StandardCharsets.UTF_8);
    }

    private static String logoSvg(String className) {
        String arcId = className.replaceAll("[^A-Za-z0-9]", "") + "SemiArc";
        return """
            <svg class='""" + className + """
            ' viewBox='0 -22 620 192' role='img' aria-label='Mahluz Semi Joias'>
              <defs>
                <path id='""" + arcId + """
            ' d='M 42 106 A 74 74 0 0 1 158 22' />
              </defs>
              <text class='logo-side'><textPath href='#""" + arcId + """
            ' startOffset='1%'>SEMI JOIAS</textPath></text>
              <text class='logo-word' x='104' y='118'>Mahluz</text>
            </svg>
            """;
    }

    private static String saveImage(Part part, String current) throws IOException {
        if (part == null || part.filename == null || part.filename.isBlank() || part.bytes.length == 0) return current;
        String extension = ".jpg";
        int dot = part.filename.lastIndexOf('.');
        if (dot >= 0 && dot < part.filename.length() - 1) extension = part.filename.substring(dot).replaceAll("[^A-Za-z0-9.]", "");
        String filename = UUID.randomUUID() + extension;
        Files.copy(new java.io.ByteArrayInputStream(part.bytes), UPLOAD_DIR.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
        return filename;
    }

    private static void serveUpload(HttpExchange exchange, String path) throws IOException {
        String name = path.substring("/uploads/".length()).replace("/", "");
        Path file = UPLOAD_DIR.resolve(name);
        if (!Files.exists(file)) {
            send(exchange, 404, "Imagem nao encontrada");
            return;
        }
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", Files.probeContentType(file) == null ? "application/octet-stream" : Files.probeContentType(file));
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(200, Files.size(file));
        try (OutputStream out = exchange.getResponseBody()) {
            Files.copy(file, out);
        }
    }

    private static Map<String, String> urlEncoded(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> data = new HashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isBlank()) continue;
            String[] kv = pair.split("=", 2);
            data.put(decode(kv[0]), kv.length > 1 ? decode(kv[1]) : "");
        }
        return data;
    }

    private static Map<String, Part> multipart(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.contains("boundary=")) throw new IllegalArgumentException("Formulario invalido");
        String boundary = "--" + contentType.substring(contentType.indexOf("boundary=") + 9);
        byte[] raw = exchange.getRequestBody().readAllBytes();
        String body = new String(raw, StandardCharsets.ISO_8859_1);
        Map<String, Part> parts = new HashMap<>();
        for (String section : body.split(java.util.regex.Pattern.quote(boundary))) {
            if (!section.contains("Content-Disposition")) continue;
            int split = section.indexOf("\r\n\r\n");
            if (split < 0) continue;
            String headers = section.substring(0, split);
            String content = section.substring(split + 4);
            if (content.endsWith("\r\n")) content = content.substring(0, content.length() - 2);
            String name = headerValue(headers, "name");
            if (name == null) continue;
            Part part = new Part();
            part.filename = headerValue(headers, "filename");
            part.bytes = content.getBytes(StandardCharsets.ISO_8859_1);
            part.text = new String(part.bytes, StandardCharsets.UTF_8).trim();
            parts.put(name, part);
        }
        return parts;
    }

    private static String headerValue(String headers, String key) {
        String token = key + "=\"";
        int start = headers.indexOf(token);
        if (start < 0) return null;
        start += token.length();
        int end = headers.indexOf('"', start);
        return end < 0 ? null : headers.substring(start, end);
    }

    private static String value(Map<String, Part> parts, String name) {
        Part p = parts.get(name);
        return p == null ? "" : p.text;
    }

    private static String decode(String text) {
        return URLDecoder.decode(text, StandardCharsets.UTF_8);
    }

    private static String page(String title, String body) {
        return "<!doctype html><html lang='pt-BR'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1'><title>" + esc(title) + "</title><link rel='preconnect' href='https://fonts.googleapis.com'><link rel='preconnect' href='https://fonts.gstatic.com' crossorigin><link href='https://fonts.googleapis.com/css2?family=Montserrat:wght@400;500;600;700;800&display=swap' rel='stylesheet'><style>" + css() + "</style></head><body><nav><a class='nav-brand' href='/'>" + logoSvg("nav-logo") + "</a><div><a href='/catalogo'>Coleções</a><a href='/admin'>Painel</a></div></nav>" + body + "<script>" + script() + "</script></body></html>";
    }

    private static String css() {
        return """
            :root{--ink:#251a12;--muted:#77685e;--paper:#f4f1ec;--surface:#fffdf9;--line:#ddd3c8;--accent:#704821;--soft:#ebe5dd;--green:#315a45;--danger:#9b2d2d;--shadow:0 20px 60px rgba(60,42,28,.12)}
            *{box-sizing:border-box}html{scroll-behavior:smooth}body{margin:0;font-family:Montserrat,Segoe UI,Arial,sans-serif;background:var(--paper);color:var(--ink)}
            nav{height:72px;display:flex;align-items:center;justify-content:space-between;gap:16px;padding:0 6vw;border-bottom:1px solid rgba(112,72,33,.16);background:rgba(244,241,236,.92);position:sticky;top:0;z-index:10;backdrop-filter:blur(14px)}
            nav div{display:flex;gap:16px;align-items:center}nav a,.back{color:var(--ink);text-decoration:none;font-weight:800;font-size:13px;letter-spacing:.02em}.nav-brand{display:flex;align-items:center}.nav-logo{width:150px;height:auto;display:block}.hero-logo{width:min(450px,86vw);height:auto;display:block}.logo-word{font-family:Georgia,Times New Roman,serif;font-size:92px;fill:var(--accent);letter-spacing:1px}.logo-side{font-family:Montserrat,Arial,sans-serif;font-size:16px;letter-spacing:2px;fill:var(--ink);font-weight:700}
            .client-hero{min-height:calc(100svh - 72px);padding:28px 6vw 34px;display:grid;align-content:space-between;gap:32px;background:linear-gradient(90deg,rgba(244,241,236,.96),rgba(244,241,236,.72),rgba(244,241,236,.26)),url('https://images.unsplash.com/photo-1515562141207-7a88fb7ce338?auto=format&fit=crop&w=1800&q=82') center/cover}
            .hero-copy{max-width:760px;animation:rise .7s ease both}.eyebrow{text-transform:uppercase;letter-spacing:.18em;font-size:11px;color:var(--accent);font-weight:800}.hero-copy h1{font-size:clamp(38px,10vw,72px);line-height:1.02;margin:10px 0 18px;font-weight:600;max-width:780px}.hero-copy p{font-size:clamp(15px,4vw,20px);line-height:1.6;color:#423328;max-width:660px}
            .button,.reserve-button{display:inline-flex;align-items:center;justify-content:center;border:0;background:var(--accent);color:white;text-decoration:none;padding:14px 18px;border-radius:999px;font-weight:800;cursor:pointer;box-shadow:0 12px 30px rgba(112,72,33,.22);transition:transform .25s ease,box-shadow .25s ease,background .25s ease}.button:hover,.reserve-button:hover{transform:translateY(-2px);box-shadow:0 18px 40px rgba(112,72,33,.28);background:#5f3a19}
            .hero-actions,.admin-menu{display:flex;gap:12px;flex-wrap:wrap;align-items:center}.secondary-button{background:transparent;color:var(--accent);border:1px solid rgba(112,72,33,.28);box-shadow:none}.secondary-button:hover{background:rgba(112,72,33,.08);color:var(--accent);box-shadow:none}.mini-button{padding:10px 14px;font-size:12px}
            .hero-note{display:flex;gap:8px;flex-wrap:wrap}.hero-note span{border:1px solid rgba(112,72,33,.22);background:rgba(255,253,249,.64);border-radius:999px;padding:9px 12px;font-size:12px;font-weight:700;color:var(--accent)}
            .boutique-main{width:min(1220px,92vw);margin:0 auto;padding:30px 0 70px}.intro-strip{padding:28px 0 18px;border-bottom:1px solid var(--line)}.intro-strip p{margin:0 0 8px;color:var(--accent);font-weight:800;text-transform:uppercase;letter-spacing:.18em;font-size:11px}.intro-strip h2{font-size:clamp(24px,7vw,44px);line-height:1.1;margin:0 0 12px;font-weight:600;max-width:760px}.intro-strip span{color:var(--muted);font-weight:600}
            .collection,.combine-section,.favorites-panel{padding:54px 0 8px}.collection-head{display:flex;align-items:end;justify-content:space-between;gap:18px;margin-bottom:22px}.collection-head h2{font-size:clamp(28px,7vw,48px);line-height:1;margin:6px 0 10px;font-weight:600}.collection-head p{max-width:560px;color:var(--muted);line-height:1.55;margin:0}
            .boutique-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:22px}.boutique-card{background:var(--surface);border:1px solid rgba(112,72,33,.14);border-radius:18px;overflow:hidden;box-shadow:0 12px 34px rgba(60,42,28,.08);transition:transform .35s ease,box-shadow .35s ease,border-color .35s ease}.boutique-card:hover{transform:translateY(-6px);box-shadow:var(--shadow);border-color:rgba(112,72,33,.28)}
            .image-wrap{position:relative;overflow:hidden;background:var(--soft)}.image-wrap img,.image-wrap .placeholder{width:100%;aspect-ratio:3/4;object-fit:cover;display:flex;align-items:center;justify-content:center;color:var(--accent);font-weight:800;letter-spacing:.2em;background:linear-gradient(140deg,var(--soft),#fffdf9);transition:transform .7s ease}.boutique-card:hover .image-wrap img{transform:scale(1.035)}
            .product-badge{position:absolute;left:12px;top:12px;background:rgba(255,253,249,.9);backdrop-filter:blur(10px);border:1px solid rgba(112,72,33,.18);border-radius:999px;padding:8px 10px;font-size:11px;font-weight:800;color:var(--accent)}.favorite-button{position:absolute;right:12px;top:12px;width:38px;height:38px;border-radius:50%;border:1px solid rgba(112,72,33,.2);background:rgba(255,253,249,.9);color:var(--accent);font-size:20px;line-height:1;cursor:pointer;transition:transform .2s ease,background .2s ease}.favorite-button:hover,.favorite-button.active{transform:scale(1.06);background:var(--accent);color:#fff}
            .boutique-card-body{padding:18px}.product-kicker{display:flex;justify-content:space-between;gap:12px;color:var(--accent);font-size:10px;font-weight:900;text-transform:uppercase;letter-spacing:.12em}.product-kicker span:last-child{text-align:right;color:var(--green)}.boutique-card h3{font-size:24px;margin:12px 0 8px;line-height:1.1;font-weight:600}.boutique-card p{font-size:14px;line-height:1.55;color:var(--muted);margin:0 0 18px}.reserve-row{display:grid;gap:12px;border-top:1px solid var(--line);padding-top:16px}.reserve-row strong{font-size:20px;color:var(--accent)}.reserve-button{width:100%;font-size:13px;padding:13px 14px}
            .combine-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:18px}.combine-card{background:var(--surface);border:1px solid rgba(112,72,33,.14);border-radius:18px;overflow:hidden;box-shadow:0 12px 34px rgba(60,42,28,.07)}.mini-images{display:grid;grid-template-columns:1fr 1fr}.mini-images img,.mini-images .placeholder{width:100%;aspect-ratio:1;object-fit:cover}.combine-card>div:last-child{padding:18px}.combine-card span{color:var(--accent);font-size:11px;font-weight:900;text-transform:uppercase;letter-spacing:.12em}.combine-card h3{margin:8px 0;font-size:20px}.combine-card p{color:var(--muted);line-height:1.5}.combine-card a{color:var(--accent);font-weight:900;text-decoration:none}
            .favorites-panel{display:grid;grid-template-columns:1fr 1.2fr;gap:24px;border-top:1px solid var(--line);margin-top:40px}.favorites-panel h2{font-size:clamp(28px,7vw,44px);margin:6px 0 10px}.favorites-panel p{color:var(--muted);line-height:1.55}.favorite-list{display:flex;gap:10px;flex-wrap:wrap;align-content:start}.favorite-list span,.favorite-pill{background:var(--surface);border:1px solid rgba(112,72,33,.14);border-radius:999px;padding:10px 12px;color:var(--accent);font-size:13px;font-weight:800}
            .wrap{width:min(1180px,90vw);margin:46px auto}.narrow{width:min(680px,90vw)}.admin h1,.wrap h1{font-size:44px}.topbar{display:flex;align-items:center;justify-content:space-between;gap:20px}.stats{display:grid;grid-template-columns:repeat(3,1fr);gap:14px;margin:24px 0}.stats div,.panel{background:#fbfaf7;border:1px solid var(--line);border-radius:8px;padding:20px}.stats b{font-size:28px;display:block}.stats span{color:var(--muted)}.panel-head{display:flex;justify-content:space-between;align-items:center;gap:16px;margin-bottom:14px}.panel-head h2{margin:0 0 6px}.panel-head p,.form-hint{margin:0;color:var(--muted);line-height:1.5}table{width:100%;border-collapse:collapse}th,td{text-align:left;border-bottom:1px solid var(--line);padding:13px 8px}th{color:var(--muted);font-size:13px}.photo-cell{width:86px}.photo-thumb{width:62px;height:62px;object-fit:cover;border-radius:8px;border:1px solid var(--line);display:block}.photo-empty{display:inline-flex;width:62px;height:62px;border-radius:8px;border:1px dashed var(--line);align-items:center;justify-content:center;font-size:10px;color:var(--muted);text-align:center}.actions{display:flex;gap:10px;align-items:center}.actions form{margin:0}.actions button{background:transparent;border:0;color:var(--danger);cursor:pointer;font:inherit}.split{display:grid;grid-template-columns:1fr 1fr;gap:28px;margin-top:20px}.form{display:grid;gap:14px}.field-grid{display:grid;grid-template-columns:1fr 1fr;gap:14px}.form label{display:grid;gap:7px;font-weight:800}.form input,.form textarea,.form select{width:100%;border:1px solid var(--line);border-radius:6px;padding:12px;background:#fff;font:inherit}.form small{color:var(--muted);font-size:12px}.current-photo{display:grid;gap:8px}.current-photo span{font-weight:800}.current-photo img,.current-photo .placeholder{width:min(220px,100%);aspect-ratio:1;object-fit:cover;border-radius:8px;border:1px solid var(--line)}.check{display:flex!important;grid-template-columns:auto 1fr;align-items:center}.check input{width:auto}.danger-check{color:var(--danger)}.timeline{display:grid;gap:12px}.timeline div{padding-bottom:12px;border-bottom:1px solid var(--line)}.timeline b,.timeline span{display:block}.timeline span{color:var(--muted);font-size:13px}small{color:var(--danger)}
            .fade-in{animation:rise .55s ease both}@keyframes rise{from{opacity:0;transform:translateY(18px)}to{opacity:1;transform:translateY(0)}}@media(max-width:980px){.boutique-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.combine-grid,.favorites-panel{grid-template-columns:1fr}}@media(max-width:640px){nav{height:66px;padding:0 5vw}nav div{gap:12px}.nav-logo{width:128px}.client-hero{min-height:calc(100svh - 66px);padding:22px 5vw 28px;background-position:center}.hero-logo{width:min(320px,86vw)}.hero-copy h1{font-size:clamp(36px,11vw,52px)}.hero-copy p{font-size:15px}.button{width:100%;padding:15px 18px}.boutique-main{width:min(100% - 28px,560px);padding-top:18px}.collection{padding-top:46px}.collection-head{display:block}.boutique-grid{grid-template-columns:1fr;gap:24px}.boutique-card{border-radius:20px}.boutique-card h3{font-size:26px}.image-wrap img,.image-wrap .placeholder{aspect-ratio:4/5}.product-kicker{font-size:9px}.hero-note span{font-size:11px}.stats,.split,.field-grid{grid-template-columns:1fr}.admin-menu,.hero-actions{display:grid}.panel-head{display:block}table{display:block;overflow:auto}.actions{min-width:130px}.wrap{margin:30px auto}.topbar{display:block}}@media(prefers-reduced-motion:reduce){*{animation:none!important;transition:none!important;scroll-behavior:auto!important}}
            """;
    }

    private static String script() {
        return """
            (()=>{const key='mahluz:favoritos';const read=()=>JSON.parse(localStorage.getItem(key)||'[]');const write=v=>localStorage.setItem(key,JSON.stringify(v));const render=()=>{const list=document.querySelector('[data-favorite-list]');if(!list)return;const favs=read();list.innerHTML=favs.length?favs.map(f=>`<button class="favorite-pill" type="button">${f.name}</button>`).join(''):'<span>Nenhuma peça favoritada ainda.</span>';document.querySelectorAll('[data-favorite-toggle]').forEach(btn=>{const active=favs.some(f=>f.id===btn.dataset.id);btn.classList.toggle('active',active);btn.textContent=active?'♥':'♡';});};document.addEventListener('click',e=>{const btn=e.target.closest('[data-favorite-toggle]');if(!btn)return;const favs=read();const exists=favs.some(f=>f.id===btn.dataset.id);write(exists?favs.filter(f=>f.id!==btn.dataset.id):[...favs,{id:btn.dataset.id,name:btn.dataset.name}]);render();});render();})();
            """;
    }

    private static void send(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(303, -1);
        exchange.close();
    }

    private static String esc(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String money(BigDecimal value) {
        return "R$ " + String.format(Locale.of("pt", "BR"), "%,.2f", value);
    }

    static class Product {
        String id;
        String name;
        String category;
        String description;
        BigDecimal price = BigDecimal.ZERO;
        int stock;
        String image;
        boolean active = true;
    }

    static class Movement {
        String id;
        String productId;
        String productName;
        String type;
        int quantity;
        String note;
        LocalDateTime when;
    }

    static class Part {
        String filename;
        byte[] bytes = new byte[0];
        String text = "";
    }

    static class Store {
        private final List<Product> products = new ArrayList<>();
        private final List<Movement> movements = new ArrayList<>();

        void load() throws IOException {
            products.clear();
            movements.clear();
            if (Files.exists(PRODUCTS_FILE)) {
                for (String line : Files.readAllLines(PRODUCTS_FILE, StandardCharsets.UTF_8)) {
                    String[] f = line.split("\t", -1);
                    if (f.length < 8) continue;
                    Product p = new Product();
                    p.id = f[0];
                    p.name = dec(f[1]);
                    p.category = dec(f[2]);
                    p.description = dec(f[3]);
                    p.price = new BigDecimal(f[4]);
                    p.stock = Integer.parseInt(f[5]);
                    p.image = dec(f[6]);
                    p.active = Boolean.parseBoolean(f[7]);
                    products.add(p);
                }
            }
            if (Files.exists(MOVEMENTS_FILE)) {
                for (String line : Files.readAllLines(MOVEMENTS_FILE, StandardCharsets.UTF_8)) {
                    String[] f = line.split("\t", -1);
                    if (f.length < 7) continue;
                    Movement m = new Movement();
                    m.id = f[0];
                    m.productId = f[1];
                    m.productName = dec(f[2]);
                    m.type = f[3];
                    m.quantity = Integer.parseInt(f[4]);
                    m.note = dec(f[5]);
                    m.when = LocalDateTime.parse(f[6]);
                    movements.add(m);
                }
            }
        }

        void seedExamplesIfNeeded() throws IOException {
            boolean hasMahluzCollection = products.stream().anyMatch(p -> p.id.startsWith("mahluz-"));
            if (Files.exists(CATALOG_SEEDED_FILE) && hasMahluzCollection) return;

            products.clear();
            movements.clear();
            addExample("mahluz-flor-preta", "Flor Preta", "Conjunto", "Conjunto com colar e brincos em formato floral preto, com acabamento dourado polido e presença elegante. Uma peça marcante para produções sofisticadas.", "149.90", 1, "");
            addExample("mahluz-gota-onyx", "Gota Ônix", "Conjunto", "Conjunto com pedra preta em formato de gota, brilho profundo e banho dourado. Ideal para quem busca uma semijoia clássica com toque moderno.", "159.90", 1, "");
            addExample("mahluz-rubi", "Rubi", "Conjunto", "Conjunto vermelho em gota, delicado e luminoso, perfeito para destacar o colo com feminilidade e elegância.", "169.90", 1, "");
            addExample("mahluz-flor-perola", "Flor Pérola", "Conjunto", "Conjunto floral branco com acabamento dourado e brilho suave. Uma escolha refinada para looks românticos e ocasiões especiais.", "149.90", 2, "");
            addExample("mahluz-flor-delicata", "Flor Delicata", "Conjunto", "Conjunto floral minimalista com banho dourado, leve e versátil para usar no dia a dia sem perder sofisticação.", "129.90", 2, "");
            addExample("mahluz-trinity", "Trinity", "Brincos", "Brinco dourado com design entrelaçado, acabamento premium e visual escultural. Traz presença elegante sem exagero.", "89.90", 1, "");
            addExample("mahluz-argola-luxo", "Argola Luxo", "Argolas", "Argola dourada canelada com brilho intenso e acabamento refinado. Uma peça essencial para compor produções modernas.", "99.90", 2, "");
            addExample("mahluz-ponto-luz", "Ponto de Luz", "Colares", "Colar delicado com pontos de brilho sutis, perfeito para combinações elegantes e sobreposições.", "119.90", 1, "");
            addExample("mahluz-esmeralda", "Esmeralda", "Conjunto", "Conjunto verde em gota com banho dourado, acabamento delicado e cor sofisticada que valoriza qualquer produção.", "169.90", 1, "");
            addExample("mahluz-safira", "Safira", "Conjunto", "Conjunto azul em gota com banho prateado, brilho frio e elegante. Uma peça moderna para ocasiões especiais.", "169.90", 1, "");
            addExample("mahluz-solitaire", "Solitaire", "Anéis", "Anel com pedra central brilhante e acabamento prateado, pensado para quem ama peças delicadas com aparência nobre.", "119.90", 1, "");
            addExample("mahluz-colors", "Colors", "Conjunto", "Conjunto colorido em pedras delicadas, com brilho alegre e acabamento refinado para produções cheias de personalidade.", "179.90", 1, "");
            addExample("mahluz-tennis-dourada", "Tennis Dourada", "Pulseiras", "Pulseira dourada com brilho contínuo e acabamento sofisticado, perfeita para usar sozinha ou com outras peças.", "139.90", 2, "");
            addExample("mahluz-coracao-cristal", "Coração Cristal", "Conjunto", "Conjunto com ponto de luz em formato de coração, delicado e feminino, ideal para presentear ou iluminar o look.", "149.90", 1, "");
            addExample("mahluz-duo-cristal", "Duo Cristal", "Conjunto", "Conjunto com colar e brincos de brilho cristalino, acabamento dourado e proposta elegante para ocasiões especiais.", "159.90", 1, "");
            addExample("mahluz-gota-verde", "Gota Verde", "Conjunto", "Conjunto com gota verde e banho dourado, combinando delicadeza, cor intensa e visual sofisticado.", "159.90", 1, "");
            addExample("mahluz-trio-esmeralda", "Trio Esmeralda", "Brincos", "Trio de brincos verdes com brilho delicado, ideal para quem gosta de opções versáteis e acabamento elegante.", "89.90", 2, "");
            addExample("mahluz-aliancas-luz", "Alianças Luz", "Anéis", "Seleção de anéis delicados com pontos de brilho, perfeitos para composições femininas e uso diário.", "79.90", 3, "");
            addExample("mahluz-ponto-prata", "Ponto Prata", "Brincos", "Brinco pequeno com acabamento prateado e brilho discreto, uma peça essencial para todos os dias.", "49.90", 2, "");
            addExample("mahluz-bracelet-prayer", "Bracelet Prayer", "Pulseiras", "Pulseira dourada com detalhe gravado e acabamento polido, trazendo elegância com significado.", "109.90", 1, "");
            addExample("mahluz-faith", "Faith", "Pulseiras", "Pulseira prateada com elos delicados e pingentes de cruz, com acabamento leve e moderno.", "119.90", 1, "");
            addExample("mahluz-heart-gold", "Heart Gold", "Conjunto", "Conjunto dourado com coração vazado e textura delicada, ideal para quem ama peças românticas e marcantes.", "139.90", 1, "");
            addExample("mahluz-lavanda", "Lavanda", "Conjunto", "Conjunto lilás com banho dourado, delicado e feminino, perfeito para trazer suavidade e brilho ao visual.", "149.90", 1, "");
            save();
            Files.writeString(CATALOG_SEEDED_FILE, "catalogo mahluz criado em " + LocalDateTime.now(), StandardCharsets.UTF_8);
        }

        private void addExample(String id, String name, String category, String description, String price, int stock, String image) {
            Product p = new Product();
            p.id = id;
            p.name = name;
            p.category = category;
            p.description = description;
            p.price = new BigDecimal(price);
            p.stock = stock;
            p.image = image;
            p.active = true;
            products.add(p);
        }

        void clearGeneratedImageRefs() throws IOException {
            boolean changed = false;
            List<String> generatedFiles = List.of(
                    "flor-preta.svg", "gota-onyx.svg", "rubi.svg", "flor-perola.svg", "flor-delicata.svg",
                    "trinity.svg", "argola-luxo.svg", "coracao-cristal.svg", "solitaire.svg");
            for (Product p : products) {
                if (generatedFiles.contains(p.image)) {
                    p.image = "";
                    changed = true;
                }
            }
            if (changed) save();
        }

        List<Product> products() {
            return products.stream().sorted(Comparator.comparing(p -> p.name.toLowerCase(Locale.ROOT))).toList();
        }

        List<Movement> movements() {
            return movements;
        }

        Optional<Product> findProduct(String id) {
            return products.stream().filter(p -> Objects.equals(p.id, id)).findFirst();
        }

        void addProduct(Product p) throws IOException {
            products.add(p);
            save();
        }

        void removeProduct(String id) throws IOException {
            products.removeIf(p -> Objects.equals(p.id, id));
            save();
        }

        void addMovement(Movement m) throws IOException {
            movements.add(m);
            save();
        }

        int totalStock() {
            return products.stream().mapToInt(p -> p.stock).sum();
        }

        BigDecimal stockValue() {
            return products.stream().map(p -> p.price.multiply(BigDecimal.valueOf(p.stock))).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        void save() throws IOException {
            Files.createDirectories(DATA_DIR);
            List<String> productLines = products.stream().map(p -> String.join("\t",
                    p.id, enc(p.name), enc(p.category), enc(p.description), p.price.toPlainString(), String.valueOf(p.stock), enc(p.image), String.valueOf(p.active))).toList();
            Files.write(PRODUCTS_FILE, productLines, StandardCharsets.UTF_8);

            List<String> movementLines = movements.stream().map(m -> String.join("\t",
                    m.id, m.productId, enc(m.productName), m.type, String.valueOf(m.quantity), enc(m.note), m.when.toString())).toList();
            Files.write(MOVEMENTS_FILE, movementLines, StandardCharsets.UTF_8);
        }

        private static String enc(String text) {
            return Base64.getUrlEncoder().withoutPadding().encodeToString((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
        }

        private static String dec(String text) {
            if (text == null || text.isBlank()) return "";
            return new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8);
        }
    }
}
