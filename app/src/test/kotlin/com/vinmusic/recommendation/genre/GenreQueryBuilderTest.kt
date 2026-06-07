package com.vinmusic.recommendation.genre

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for GenreQueryBuilder
 * 
 * Tests verify that:
 * - All query functions return non-empty lists for valid inputs
 * - Query generation is deterministic
 * - Sub-genre specific queries are generated correctly
 */
class GenreQueryBuilderTest {
    
    @Test
    fun `buildRapQueries with Trap sub-genre returns non-empty list`() {
        val queries = GenreQueryBuilder.buildRapQueries("Trap")
        
        assertFalse("Trap queries should not be empty", queries.isEmpty())
        assertTrue("Trap queries should contain trap-specific terms", 
            queries.any { it.contains("trap", ignoreCase = true) })
    }
    
    @Test
    fun `buildRapQueries with Old School sub-genre returns non-empty list`() {
        val queries = GenreQueryBuilder.buildRapQueries("Old School")
        
        assertFalse("Old School queries should not be empty", queries.isEmpty())
        assertTrue("Old School queries should contain old school/classic terms", 
            queries.any { it.contains("old school", ignoreCase = true) || 
                         it.contains("classic", ignoreCase = true) })
    }
    
    @Test
    fun `buildRapQueries with Desi Hip-Hop sub-genre returns non-empty list`() {
        val queries = GenreQueryBuilder.buildRapQueries("Desi Hip-Hop")
        
        assertFalse("Desi Hip-Hop queries should not be empty", queries.isEmpty())
        assertTrue("Desi Hip-Hop queries should contain desi/hindi/indian terms", 
            queries.any { it.contains("desi", ignoreCase = true) || 
                         it.contains("hindi", ignoreCase = true) || 
                         it.contains("indian", ignoreCase = true) })
    }
    
    @Test
    fun `buildRapQueries with UK Drill sub-genre returns non-empty list`() {
        val queries = GenreQueryBuilder.buildRapQueries("UK Drill")
        
        assertFalse("UK Drill queries should not be empty", queries.isEmpty())
        assertTrue("UK Drill queries should contain uk drill terms", 
            queries.any { it.contains("uk drill", ignoreCase = true) })
    }
    
    @Test
    fun `buildRapQueries with null sub-genre returns generic rap queries`() {
        val queries = GenreQueryBuilder.buildRapQueries(null)
        
        assertFalse("Generic rap queries should not be empty", queries.isEmpty())
        assertTrue("Generic rap queries should contain rap/hip hop terms", 
            queries.any { it.contains("rap", ignoreCase = true) || 
                         it.contains("hip hop", ignoreCase = true) })
    }
    
    @Test
    fun `buildRapQueries is case insensitive`() {
        val queriesLower = GenreQueryBuilder.buildRapQueries("trap")
        val queriesUpper = GenreQueryBuilder.buildRapQueries("TRAP")
        val queriesMixed = GenreQueryBuilder.buildRapQueries("TrAp")
        
        assertEquals("Queries should be the same regardless of case", queriesLower, queriesUpper)
        assertEquals("Queries should be the same regardless of case", queriesLower, queriesMixed)
    }
    
    @Test
    fun `buildRapArtistQueries returns non-empty list`() {
        val queries = GenreQueryBuilder.buildRapArtistQueries()
        
        assertFalse("Artist queries should not be empty", queries.isEmpty())
        assertTrue("Should include at least 4 specific artists plus trending queries", 
            queries.size >= 5)
    }
    
    @Test
    fun `buildRapArtistQueries includes required artists`() {
        val queries = GenreQueryBuilder.buildRapArtistQueries()
        val queryString = queries.joinToString(" ").lowercase()
        
        assertTrue("Should include Kendrick Lamar", 
            queryString.contains("kendrick lamar"))
        assertTrue("Should include Divine", 
            queryString.contains("divine"))
        assertTrue("Should include Kr\$na", 
            queryString.contains("kr\$na"))
        assertTrue("Should include Eminem", 
            queryString.contains("eminem"))
    }
    
    @Test
    fun `buildKPopQueries with dance practice content type returns non-empty list`() {
        val queries = GenreQueryBuilder.buildKPopQueries("dance practice")
        
        assertFalse("Dance practice queries should not be empty", queries.isEmpty())
        assertTrue("Should contain dance practice/choreography terms", 
            queries.any { it.contains("dance practice", ignoreCase = true) || 
                         it.contains("choreography", ignoreCase = true) })
    }
    
    @Test
    fun `buildKPopQueries with live stage content type returns non-empty list`() {
        val queries = GenreQueryBuilder.buildKPopQueries("live stage")
        
        assertFalse("Live stage queries should not be empty", queries.isEmpty())
        assertTrue("Should contain live performance/stage terms", 
            queries.any { it.contains("live", ignoreCase = true) || 
                         it.contains("stage", ignoreCase = true) })
    }
    
    @Test
    fun `buildKPopQueries with trending groups returns non-empty list`() {
        val queries = GenreQueryBuilder.buildKPopQueries("trending groups")
        
        assertFalse("Trending groups queries should not be empty", queries.isEmpty())
        assertTrue("Should include specific K-Pop groups", 
            queries.size >= 3)
    }
    
    @Test
    fun `buildKPopQueries with top soloists returns non-empty list`() {
        val queries = GenreQueryBuilder.buildKPopQueries("top soloists")
        
        assertFalse("Top soloists queries should not be empty", queries.isEmpty())
        assertTrue("Should include specific K-Pop soloists", 
            queries.size >= 3)
    }
    
    @Test
    fun `buildKPopQueries with null content type returns generic kpop queries`() {
        val queries = GenreQueryBuilder.buildKPopQueries(null)
        
        assertFalse("Generic K-Pop queries should not be empty", queries.isEmpty())
        assertTrue("Should contain kpop terms", 
            queries.any { it.contains("kpop", ignoreCase = true) })
    }
    
    @Test
    fun `build90sQueries with specific year returns non-empty list`() {
        val year = 1995
        val queries = GenreQueryBuilder.build90sQueries(year)
        
        assertFalse("90s queries should not be empty", queries.isEmpty())
        assertTrue("All queries should contain the year", 
            queries.all { it.contains(year.toString()) })
    }
    
    @Test
    fun `build90sQueries with null year defaults to 1995`() {
        val queries = GenreQueryBuilder.build90sQueries(null)
        
        assertFalse("Default 90s queries should not be empty", queries.isEmpty())
        assertTrue("Should default to 1995", 
            queries.all { it.contains("1995") })
    }
    
    @Test
    fun `build90sQueries works for all years 1990-1999`() {
        for (year in 1990..1999) {
            val queries = GenreQueryBuilder.build90sQueries(year)
            
            assertFalse("Queries for year $year should not be empty", queries.isEmpty())
            assertTrue("All queries should contain year $year", 
                queries.all { it.contains(year.toString()) })
        }
    }
    
    @Test
    fun `buildIndieUndiscoveredQueries returns non-empty list`() {
        val queries = GenreQueryBuilder.buildIndieUndiscoveredQueries()
        
        assertFalse("Indie undiscovered queries should not be empty", queries.isEmpty())
        assertTrue("Should contain indie discovery terms", 
            queries.any { it.contains("indie", ignoreCase = true) && 
                         (it.contains("unsigned", ignoreCase = true) || 
                          it.contains("underrated", ignoreCase = true) || 
                          it.contains("hidden", ignoreCase = true)) })
    }
    
    @Test
    fun `buildIndieAcousticQueries returns non-empty list`() {
        val queries = GenreQueryBuilder.buildIndieAcousticQueries()
        
        assertFalse("Indie acoustic queries should not be empty", queries.isEmpty())
        assertTrue("Should contain indie acoustic terms", 
            queries.any { it.contains("indie", ignoreCase = true) && 
                         (it.contains("acoustic", ignoreCase = true) || 
                          it.contains("unplugged", ignoreCase = true) || 
                          it.contains("stripped", ignoreCase = true)) })
    }
    
    @Test
    fun `buildFromTemplate with no variables returns original templates`() {
        val template = GenreQueryTemplate(
            genre = "test",
            templates = listOf("query one", "query two", "query three")
        )
        
        val queries = GenreQueryBuilder.buildFromTemplate(template)
        
        assertEquals("Should return all templates unchanged", 
            template.templates, queries)
    }
    
    @Test
    fun `buildFromTemplate with variables substitutes correctly`() {
        val template = GenreQueryTemplate(
            genre = "test",
            templates = listOf(
                "{artist} popular songs",
                "{artist} greatest hits",
                "best of {artist}"
            )
        )
        val vars = mapOf("artist" to "BTS")
        
        val queries = GenreQueryBuilder.buildFromTemplate(template, vars)
        
        assertEquals("Should have same number of queries", template.templates.size, queries.size)
        assertTrue("All queries should contain BTS", 
            queries.all { it.contains("BTS") })
        assertFalse("No queries should contain placeholder", 
            queries.any { it.contains("{artist}") })
    }
    
    @Test
    fun `buildFromTemplate with multiple variables substitutes all`() {
        val template = GenreQueryTemplate(
            genre = "test",
            templates = listOf("{genre} {year} hits")
        )
        val vars = mapOf("genre" to "rock", "year" to "1995")
        
        val queries = GenreQueryBuilder.buildFromTemplate(template, vars)
        
        assertEquals("rock 1995 hits", queries.first())
    }
    
    @Test
    fun `query functions are deterministic`() {
        // Test that calling the same function multiple times produces identical results
        repeat(5) {
            val queries1 = GenreQueryBuilder.buildRapQueries("Trap")
            val queries2 = GenreQueryBuilder.buildRapQueries("Trap")
            assertEquals("Queries should be deterministic", queries1, queries2)
        }
        
        repeat(5) {
            val queries1 = GenreQueryBuilder.buildKPopQueries("dance practice")
            val queries2 = GenreQueryBuilder.buildKPopQueries("dance practice")
            assertEquals("Queries should be deterministic", queries1, queries2)
        }
        
        repeat(5) {
            val queries1 = GenreQueryBuilder.build90sQueries(1995)
            val queries2 = GenreQueryBuilder.build90sQueries(1995)
            assertEquals("Queries should be deterministic", queries1, queries2)
        }
    }
}
