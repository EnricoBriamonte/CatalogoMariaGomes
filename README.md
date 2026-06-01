# Catalogo MAHLUZ Semi Joias

Aplicacao Java simples para vender semijoias e bolsas com:

- pagina de cliente em `/catalogo`;
- vitrine publica minimalista com a marca MAHLUZ;
- cadastro de produtos com foto;
- controle de estoque;
- registro de entradas/compras e saidas/vendas;
- dados salvos localmente em `data/`;
- fotos salvas localmente em `uploads/`.

## Como rodar

No Windows, clique duas vezes em `iniciar-catalogo.bat`.

Ou rode pelo terminal:

```powershell
javac CatalogoMariaGomes.java
java -cp . CatalogoMariaGomes
```

Depois abra:

- Pagina do cliente/catalogo: <http://localhost:8080/catalogo>
- Vitrine: <http://localhost:8080>
- Painel: <http://localhost:8080/admin>

## Como usar

1. Entre no painel.
2. Clique em `Novo produto`.
3. Cadastre nome, categoria, preco, estoque, descricao e foto.
4. Use `Registrar entrada ou venda` para atualizar o estoque.

## Publicar no Render

Este projeto ja esta preparado para deploy no Render usando Docker.

1. Suba estes arquivos para um repositorio no GitHub.
2. Entre no Render.
3. Clique em `New +` e escolha `Blueprint`.
4. Conecte o repositorio `CatalogoMariaGomes`.
5. Confirme a criacao do servico.

O Render usa o arquivo `render.yaml` e o `Dockerfile` automaticamente.

Depois de publicado, acesse:

- Catalogo: `https://SEU-SITE.onrender.com/catalogo`
- Painel: `https://SEU-SITE.onrender.com/admin`

Observacao: em hospedagem gratuita, arquivos enviados pelo painel podem ser perdidos em reinicios ou novos deploys. Para uso definitivo, o ideal e salvar fotos em Cloudinary/Supabase e produtos em banco de dados.
