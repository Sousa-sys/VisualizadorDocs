# Visualizador e Editor de Documentos (Android 8.0+)

Abre documentos no próprio aparelho, sem permissão de internet e sem bibliotecas externas.

| Formato | O que aparece |
|---|---|
| PDF | páginas renderizadas (sem zoom nem busca nesta versão) |
| DOCX | texto, títulos, negrito/itálico/sublinhado, tabelas, imagens (layout simplificado) |
| XLSX | abas, valores e datas (sem cores, gráficos ou formatação de moeda/percentual) |
| PPTX | texto e imagens de cada slide (sem o layout) |
| ODT, ODS, ODP | texto, tabelas, imagens (layout simplificado) |
| EPUB | capítulos e imagens em rolagem contínua |
| RTF | somente texto |
| CSV, TSV, Markdown, TXT, código, JSON, XML | texto ou tabela |
| HTML, JPG, PNG, GIF, WEBP, BMP, SVG | exibição direta (HTML sem scripts) |
| ZIP | lista de arquivos |

Não suportados: DOC, XLS, PPT antigos, arquivos com senha, MOBI, CBR, DWG, entre outros.

## Edição
Toque em **Editar** e depois em **Salvar como**. O original não é alterado.

| Formato | O que dá para editar | Limites |
|---|---|---|
| TXT, MD, CSV, JSON, XML, HTML, código | arquivo inteiro | até 200 mil caracteres |
| DOCX, PPTX | texto de cada parágrafo | a formatação dentro de um parágrafo alterado passa a ser a do primeiro trecho; não cria parágrafos; sem cabeçalhos, rodapés e notas |
| XLSX | células já preenchidas | não cria células nem edita fórmulas |
| ODT, ODS, ODP | trechos de texto existentes | não cria conteúdo; não edita fórmulas |

Também editáveis: modelos e variantes do Office e OpenDocument, EPUB (trechos de texto), RTF (código-fonte)
e outros arquivos de texto.

Somente leitura: PDF, imagens, ZIP e DOC/XLS/PPT antigos (estes nem abrem).

## Estado dos testes
Conversão e edição foram testadas em computador (Java 21) com arquivos de exemplo e entradas corrompidas.
A tela passou apenas por checagem de tipos: o app **ainda não foi compilado nem executado em Android**.

## Gerar o APK
**Android Studio:** abra esta pasta, aguarde a sincronização e use Build > Build APK(s).

**GitHub (sem instalar nada):** envie o conteúdo desta pasta (incluindo `.github`) para um repositório,
abra a aba Actions > "Gerar APK" e baixe o artefato `visualizador-apk`.

O APK gerado é de depuração, para uso próprio. Publicar na Play Store exige assinatura própria.
