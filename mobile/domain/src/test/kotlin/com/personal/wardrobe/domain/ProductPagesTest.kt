package com.personal.wardrobe.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phone-side product import must read real page markup without a PC or an AI key. */
class ProductPagesTest {
    private val page="https://shop.example/items/linen-shirt"

    @Test fun readsJsonLdProductAndOffer() {
        val html="""
            <html><head><title>Fallback title</title>
            <script type="application/ld+json">
            {"@context":"https://schema.org","@type":"Product","name":"Linen Camp Shirt",
             "brand":{"@type":"Brand","name":"North Loom"},
             "color":"Ecru","material":"100% linen","image":["/media/shirt.jpg"],
             "offers":{"@type":"Offer","price":"89.00","priceCurrency":"USD","availability":"InStock"}}
            </script></head><body></body></html>
        """.trimIndent()
        val result=ProductPages.parse(html,page,"2026-11-07T10:00:00Z")
        val products=result["products"].asJsonArray
        assertEquals(1,products.size())
        val product=products[0].asJsonObject
        assertEquals("Linen Camp Shirt",product["name"].asString)
        assertEquals("North Loom",product["brand"].asString)
        assertEquals("100% linen",product["composition"].asString)
        assertEquals("89.00",product["price"].asString)
        assertEquals("USD",product["currency"].asString)
        assertEquals("https://shop.example/media/shirt.jpg",product["images"].asJsonArray[0].asString)
        assertTrue(product["needsConfirmation"].asBoolean)
    }

    @Test fun fallsBackToOpenGraphAndDecodesEntities() {
        val html="""
            <html><head>
            <title>Silk &amp; Wool Scarf</title>
            <meta property="og:title" content="Silk &amp; Wool Scarf">
            <meta property="og:image" content="/img/scarf.jpg">
            <meta property="product:price:amount" content="120">
            <meta property="product:price:currency" content="EUR">
            </head></html>
        """.trimIndent()
        val product=ProductPages.parse(html,page,"now")["products"].asJsonArray[0].asJsonObject
        assertEquals("Silk & Wool Scarf",product["name"].asString)
        assertEquals("120",product["price"].asString)
        assertEquals("EUR",product["currency"].asString)
        assertEquals("https://shop.example/img/scarf.jpg",product["images"].asJsonArray[0].asString)
    }

    @Test fun ignoresPrivateAndNonHttpsImages() {
        val html="""
            <html><head><script type="application/ld+json">
            {"@type":"Product","name":"Coat","image":["http://shop.example/a.jpg","https://cdn.example/b.jpg","/c.jpg"]}
            </script></head></html>
        """.trimIndent()
        val images=ProductPages.parse(html,page,"now")["products"].asJsonArray[0].asJsonObject["images"].asJsonArray
        assertEquals(2,images.size())
        assertEquals("https://cdn.example/b.jpg",images[0].asString)
        assertEquals("https://shop.example/c.jpg",images[1].asString)
    }

    @Test fun returnsAConfirmableFallbackWhenMarkupHasNoProduct() {
        val html="<html><head><title>Shop</title></head><body>Nothing structured</body></html>"
        val product=ProductPages.parse(html,page,"now")["products"].asJsonArray[0].asJsonObject
        assertEquals("Shop",product["name"].asString)
        assertEquals(page,product["url"].asString)
        assertTrue(product["needsConfirmation"].asBoolean)
    }
}
