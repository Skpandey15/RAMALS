import { type MasterySkill } from './api';

function formatScore(value: number): string {
  return value.toFixed(2);
}

/** Shows the learner's mastery score, evidence confidence, and status per skill. */
export function MasteryMap({ skills }: { skills: readonly MasterySkill[] }) {
  return (
    <section aria-labelledby="mastery-heading" className="panel">
      <h2 id="mastery-heading">Mastery map</h2>
      {skills.length === 0 ? (
        <p>No mastery yet. Complete the diagnostic to build your first mastery map.</p>
      ) : (
        <table className="mastery-table">
          <caption className="visually-hidden">Mastery score, evidence confidence, and status per skill</caption>
          <thead>
            <tr>
              <th scope="col">Skill</th>
              <th scope="col">Mastery</th>
              <th scope="col">Confidence</th>
              <th scope="col">Status</th>
            </tr>
          </thead>
          <tbody>
            {skills.map((skill) => (
              <tr key={skill.skillCode}>
                <th scope="row">{skill.skillCode}</th>
                <td>{formatScore(skill.masteryScore)}</td>
                <td>{formatScore(skill.evidenceConfidence)}</td>
                <td>{skill.masteryStatus}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
