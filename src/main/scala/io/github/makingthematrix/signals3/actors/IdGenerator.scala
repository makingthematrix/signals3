package io.github.makingthematrix.signals3.actors

import java.util.UUID
import scala.util.Random

object IdGenerator {
	private val adjectives: Array[String] = Array(
		"afraid", "aggressive", "alive", "angry", "anxious", "bad", "beautiful", "bitter", "black", "blue",
		"bold", "brave", "bright", "broad", "brown", "busy", "calm", "careful", "cheerful", "circular",
		"clean", "clear", "clever", "cold", "confident", "cool", "crooked", "cruel", "curious", "dark",
		"deep", "delighted", "dry", "dull", "eager", "early", "easy", "empty", "excited", "fast",
		"flat", "fresh", "friendly", "gentle", "glad", "gloomy", "good", "grand", "great", "green",
		"happy", "hard", "heavy", "hollow", "hot", "huge", "hungry", "innocent", "jolly", "joyful",
		"kind", "large", "late", "light", "little", "lonely", "long", "loud", "lucky", "massive",
		"narrow", "neat", "nervous", "new", "old", "orange", "pink", "proud", "purple", "quick",
		"quiet", "red", "round", "sad", "safe", "sharp", "short", "silly", "simple", "small",
		"smooth", "soft", "sour", "square", "straight", "strong", "sweet", "tall", "tiny", "warm"
	)
	
	private val nouns: Array[String] = Array(
		"acorn", "anchor", "anvil", "apple", "arrow", "badger", "bark", "barrel", "beaver", "birch",
		"blade", "branch", "brush", "cedar", "chisel", "claw", "clover", "cobra", "crane", "crow",
		"deer", "dolphin", "dove", "eagle", "elbow", "elm", "falcon", "feather", "fern", "finch",
		"fir", "fox", "frog", "hammer", "hawk", "hazel", "hedge", "heron", "horn", "horse",
		"hound", "ivy", "jaw", "juniper", "knife", "lark", "leaf", "lily", "lion", "lizard",
		"maple", "moss", "needle", "nest", "oak", "otter", "owl", "palm", "panther", "pine",
		"plow", "poplar", "raven", "reed", "robin", "root", "rose", "sage", "salmon", "saw",
		"scale", "seed", "shark", "shovel", "sickle", "spine", "spruce", "stem", "swan", "talon",
		"thorn", "throat", "thumb", "tiger", "tongue", "tooth", "trout", "tulip", "turtle", "twig",
		"vine", "viper", "walnut", "willow", "wing", "wolf", "wood", "worm", "wren", "wrench"
	)
	
	private var seed: Option[Long] = None
	private lazy val random = seed.map(s => new Random(s)).getOrElse(new Random())
	
	def setSeed(seed: Long): Boolean = 
		if (this.seed.isDefined) false 
		else { this.seed = Some(seed); true }
	
	def generate(prefix: String = ""): String = {
		val adj = adjectives(random.between(0, adjectives.length))
		val nou = nouns(random.between(0, nouns.length))
		val uuid = UUID.randomUUID().toString
		s"$prefix${adj}-${nou}-$uuid"
	}
}
