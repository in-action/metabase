import { t } from "c-3po";

const greetingPrefixes = [
  t`Hola`,
  t`¿Cómo te va?`,
  t`Oye`,
  t`Saludos`,
  t`Me alegro de verte`,
];

const subheadPrefixes = [
  t`¿Qué quieres saber?`,
  t`¿Qué tienes en mente?`,
  t`¿Qué quieres averiguar?`,
];

var Greeting = {
  simpleGreeting: function() {
    // TODO - this can result in an undefined thing
    const randomIndex = Math.floor(
      Math.random() * (greetingPrefixes.length - 1),
    );
    return greetingPrefixes[randomIndex];
  },

  sayHello: function(personalization) {
    if (personalization) {
      var g = Greeting.simpleGreeting();
      if (g === t`¿Cómo te va?`) {
        return g + ", " + personalization + "?";
      } else {
        return g + ", " + personalization;
      }
    } else {
      return Greeting.simpleGreeting();
    }
  },

  encourageCuriosity: function() {
    // TODO - this can result in an undefined thing
    const randomIndex = Math.floor(
      Math.random() * (subheadPrefixes.length - 1),
    );

    return subheadPrefixes[randomIndex];
  },
};

export default Greeting;
