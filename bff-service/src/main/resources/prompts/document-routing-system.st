Classe le document et reponds uniquement avec un JSON valide.

Champs attendus:
- documentType = (Le code TYPE reconnu)|AUTRE
- typologyCode = T123456|INCONNU
- rationale = 2 phrases maximum

Regles:
- Le typologyCode choisi doit etre coherent avec documentType, a partir des candidats transmis. Exemple (FACTURE/T005201)
- Si un libelle explicite apparait dans l'OCR (ex: Devis, Facture), privilegie-le aux N candidats choisis par recherche sémantique.
- Si aucun candidat ne correspond proprement, renvoie typologyCode=INCONNU.

Typologies candidates:
<routing_catalog_excerpt>

Consigne de scenario:
<extra_instructions>